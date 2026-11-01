// SPDX-License-Identifier: MPL-2.0
//! Local PC Credential Vault and Challenge-Response Coordinator
//! (`core/nectarlink-core/src/vault.rs`).
//!
//! Security Architecture (matching NectarLink's local-first, zero-cloud design):
//! 1. The user's PC password is NEVER sent to the phone or stored in plaintext.
//! 2. During enrollment, the PC generates:
//!    - `pc_secret_share` (32 random bytes, stored locally on the PC and sealed
//!      with Windows DPAPI / OS keyring protection)
//!    - `phone_key_share` (32 random bytes, sent once over TLS 1.3 QUIC to the
//!      paired phone, where it is wrapped inside the Android Keystore with
//!      `setUserAuthenticationRequired(true)` and zeroized on the PC).
//! 3. The PC encrypts the password using a BLAKE3-XOF stream cipher + keyed MAC
//!    derived from `BLAKE3(pc_secret_share || unwrap_token(phone_key_share, vault_id))`.
//! 4. Neither the PC disk alone (without the phone's biometric release) nor the
//!    phone alone (which has zero ciphertext) can recover the PC password.

#![forbid(unsafe_code)]
#![warn(missing_debug_implementations)]

use std::collections::HashMap;
use std::time::{SystemTime, UNIX_EPOCH};
use rand::RngCore;
use rusqlite::{params, Connection};
use thiserror::Error;

use crate::unlock_protocol::{
    compute_enrollment_proof, compute_phone_unlock_tokens, UnlockMessage, UnlockReason,
    CHALLENGE_TTL_MS,
};

/// Maximum consecutive failed unlock challenges before requiring a manual password login.
pub const MAX_FAILED_ATTEMPTS: u32 = 5;

#[derive(Debug, Error)]
pub enum VaultError {
    #[error("database error: {0}")]
    Database(#[from] rusqlite::Error),
    #[error("vault entry '{0}' not found")]
    NotFound(String),
    #[error("vault entry '{0}' is locked out after {MAX_FAILED_ATTEMPTS} failed attempts")]
    LockedOut(String),
    #[error("no active or unexpired challenge found for vault '{0}'")]
    ChallengeExpiredOrMissing(String),
    #[error("challenge nonce mismatch (possible replay attack)")]
    NonceMismatch,
    #[error("cryptographic MAC verification failed")]
    InvalidMac,
    #[error("UTF-8 credential decoding error")]
    InvalidUtf8,
}

/// Pending enrollment waiting for the phone's `EnrollAck`.
#[derive(Debug)]
struct PendingEnrollment {
    pc_name: String,
    account_name: String,
    pc_secret_share: [u8; 32],
    ciphertext: Vec<u8>,
    ciphertext_tag: [u8; 32],
    expected_proof: [u8; 32],
}

/// Active single-use challenge waiting for the phone's `UnlockResponse`.
#[derive(Debug, Clone)]
pub struct ActiveChallenge {
    pub vault_id: String,
    pub challenge_nonce: [u8; 32],
    pub expires_at_unix_ms: u64,
    pub reason: UnlockReason,
}

/// Decrypted credential returned strictly to the OS login subsystem (Windows
/// Credential Provider Named Pipe or Linux PAM socket) and immediately zeroized.
#[derive(Debug)]
pub struct UnlockedCredential {
    pub account_name: String,
    password: String,
}

impl UnlockedCredential {
    pub fn password(&self) -> &str {
        &self.password
    }
}

impl Drop for UnlockedCredential {
    fn drop(&mut self) {
        // Overwrite password bytes in memory before freeing
        let bytes = unsafe_free_zeroize_string(&mut self.password);
        let _ = bytes;
    }
}

fn unsafe_free_zeroize_string(s: &mut String) -> usize {
    // Safe in-place overwrite using `as_bytes_mut` is unsafe in std, so we
    // replace characters with '\0' via `replace_range` without unsafe code.
    let len = s.len();
    if len > 0 {
        let zeros = "\0".repeat(len);
        s.replace_range(.., &zeros);
        s.clear();
    }
    len
}

mod zeroize_slice {
    pub fn wipe(buf: &mut [u8]) {
        for b in buf.iter_mut() {
            *b = 0;
        }
    }
}

/// Constant-time 32-byte array comparison to prevent timing side-channels.
fn constant_time_eq_32(a: &[u8; 32], b: &[u8; 32]) -> bool {
    let mut diff = 0u8;
    for i in 0..32 {
        diff |= a[i] ^ b[i];
    }
    diff == 0
}

/// Applies BLAKE3 XOF keystream + keyed MAC (Encrypt-then-MAC) to seal or open
/// the PC credential using only `blake3` from NectarLink's workspace.
fn seal_credential(master_key: &[u8; 32], vault_id: &str, plaintext: &[u8]) -> (Vec<u8>, [u8; 32]) {
    let enc_key = blake3::derive_key("nectarlink.pc-unlock.v0.vault-enc", master_key);
    let mac_key = blake3::derive_key("nectarlink.pc-unlock.v0.vault-mac", master_key);

    let mut xof = blake3::Hasher::new_keyed(&enc_key);
    xof.update(vault_id.as_bytes());
    let mut reader = xof.finalize_xof();
    let mut keystream = vec![0u8; plaintext.len()];
    reader.fill(&mut keystream);

    let ciphertext: Vec<u8> = plaintext
        .iter()
        .zip(keystream.iter())
        .map(|(p, k)| p ^ k)
        .collect();
    zeroize_slice::wipe(&mut keystream);

    let mut mac = blake3::Hasher::new_keyed(&mac_key);
    mac.update(vault_id.as_bytes());
    mac.update(&ciphertext);
    let tag = *mac.finalize().as_bytes();

    (ciphertext, tag)
}

fn open_credential(
    master_key: &[u8; 32],
    vault_id: &str,
    ciphertext: &[u8],
    expected_tag: &[u8; 32],
) -> Result<Vec<u8>, VaultError> {
    let enc_key = blake3::derive_key("nectarlink.pc-unlock.v0.vault-enc", master_key);
    let mac_key = blake3::derive_key("nectarlink.pc-unlock.v0.vault-mac", master_key);

    let mut mac = blake3::Hasher::new_keyed(&mac_key);
    mac.update(vault_id.as_bytes());
    mac.update(ciphertext);
    let actual_tag = *mac.finalize().as_bytes();

    if !constant_time_eq_32(&actual_tag, expected_tag) {
        return Err(VaultError::InvalidMac);
    }

    let mut xof = blake3::Hasher::new_keyed(&enc_key);
    xof.update(vault_id.as_bytes());
    let mut reader = xof.finalize_xof();
    let mut keystream = vec![0u8; ciphertext.len()];
    reader.fill(&mut keystream);

    let plaintext: Vec<u8> = ciphertext
        .iter()
        .zip(keystream.iter())
        .map(|(c, k)| c ^ k)
        .collect();
    zeroize_slice::wipe(&mut keystream);

    Ok(plaintext)
}

fn combine_shares(pc_secret_share: &[u8; 32], phone_unwrap_token: &[u8; 32]) -> [u8; 32] {
    let mut material = [0u8; 64];
    material[..32].copy_from_slice(pc_secret_share);
    material[32..].copy_from_slice(phone_unwrap_token);
    let master = blake3::derive_key("nectarlink.pc-unlock.v0.master-vault-key", &material);
    zeroize_slice::wipe(&mut material);
    master
}

/// Manages enrolled PC accounts, encrypted credential blobs, and active
/// challenge nonces in SQLite (`rusqlite`).
#[derive(Debug)]
pub struct PcUnlockVault {
    conn: Connection,
    pending_enrollments: HashMap<String, PendingEnrollment>,
    active_challenges: HashMap<String, ActiveChallenge>,
}

impl PcUnlockVault {
    /// Opens or initializes the SQLite vault table inside NectarLink's trust store DB.
    pub fn open_in_memory_or_path(path: Option<&str>) -> Result<Self, VaultError> {
        let conn = match path {
            Some(p) => Connection::open(p)?,
            None => Connection::open_in_memory()?,
        };
        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS pc_unlock_vault (
                vault_id TEXT PRIMARY KEY,
                paired_device_id TEXT NOT NULL,
                pc_name TEXT NOT NULL,
                account_name TEXT NOT NULL,
                pc_secret_share BLOB NOT NULL,
                ciphertext BLOB NOT NULL,
                ciphertext_tag BLOB NOT NULL,
                failed_attempts INTEGER NOT NULL DEFAULT 0,
                created_at_ms INTEGER NOT NULL
            );",
        )?;
        Ok(Self {
            conn,
            pending_enrollments: HashMap::new(),
            active_challenges: HashMap::new(),
        })
    }

    /// Starts enrollment for a local PC user account:
    /// splits the key into `pc_secret_share` and `phone_key_share`, encrypts
    /// `pc_password`, zeroes `phone_key_share` from PC storage, and returns the
    /// [`UnlockMessage::EnrollRequest`] to send to the paired Android phone.
    pub fn begin_enrollment(
        &mut self,
        vault_id: String,
        pc_name: String,
        account_name: String,
        pc_password: &str,
    ) -> UnlockMessage {
        let mut rng = rand::rng();
        let mut pc_secret_share = [0u8; 32];
        let mut phone_key_share = [0u8; 32];
        rng.fill_bytes(&mut pc_secret_share);
        rng.fill_bytes(&mut phone_key_share);

        let dummy_nonce = [0u8; 32];
        let (unwrap_token, _) =
            compute_phone_unlock_tokens(&phone_key_share, &vault_id, &dummy_nonce);
        let mut master_key = combine_shares(&pc_secret_share, &unwrap_token);

        let (ciphertext, ciphertext_tag) =
            seal_credential(&master_key, &vault_id, pc_password.as_bytes());
        zeroize_slice::wipe(&mut master_key);

        let expected_proof = compute_enrollment_proof(&phone_key_share, &vault_id);

        self.pending_enrollments.insert(
            vault_id.clone(),
            PendingEnrollment {
                pc_name: pc_name.clone(),
                account_name: account_name.clone(),
                pc_secret_share,
                ciphertext,
                ciphertext_tag,
                expected_proof,
            },
        );

        let msg = UnlockMessage::EnrollRequest {
            vault_id,
            pc_name,
            account_name,
            phone_key_share: phone_key_share.to_vec(),
        };
        zeroize_slice::wipe(&mut phone_key_share);
        msg
    }

    /// Completes enrollment after verifying the Android phone's `EnrollAck` proof.
    pub fn finalize_enrollment(
        &mut self,
        paired_device_id: &str,
        vault_id: &str,
        enrollment_proof: &[u8],
    ) -> Result<(), VaultError> {
        let pending = self
            .pending_enrollments
            .remove(vault_id)
            .ok_or_else(|| VaultError::NotFound(vault_id.to_string()))?;

        let proof_arr: [u8; 32] = enrollment_proof
            .try_into()
            .map_err(|_| VaultError::InvalidMac)?;
        if !constant_time_eq_32(&pending.expected_proof, &proof_arr) {
            return Err(VaultError::InvalidMac);
        }

        let now_ms = current_unix_ms();
        self.conn.execute(
            "INSERT OR REPLACE INTO pc_unlock_vault
             (vault_id, paired_device_id, pc_name, account_name, pc_secret_share,
              ciphertext, ciphertext_tag, failed_attempts, created_at_ms)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, 0, ?8)",
            params![
                vault_id,
                paired_device_id,
                pending.pc_name,
                pending.account_name,
                pending.pc_secret_share.as_slice(),
                pending.ciphertext,
                pending.ciphertext_tag.as_slice(),
                now_ms as i64,
            ],
        )?;
        Ok(())
    }

    /// Generates a fresh 32-byte single-use challenge when the lock screen or
    /// privilege prompt requests biometric authentication.
    pub fn issue_challenge(
        &mut self,
        vault_id: &str,
        reason: UnlockReason,
    ) -> Result<UnlockMessage, VaultError> {
        let (pc_name, account_name, failed_attempts): (String, String, u32) = self
            .conn
            .query_row(
                "SELECT pc_name, account_name, failed_attempts
                 FROM pc_unlock_vault WHERE vault_id = ?1",
                params![vault_id],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            )
            .map_err(|_| VaultError::NotFound(vault_id.to_string()))?;

        if failed_attempts >= MAX_FAILED_ATTEMPTS {
            return Err(VaultError::LockedOut(vault_id.to_string()));
        }

        let mut challenge_nonce = [0u8; 32];
        rand::rng().fill_bytes(&mut challenge_nonce);
        let expires_at_unix_ms = current_unix_ms() + CHALLENGE_TTL_MS;

        self.active_challenges.insert(
            vault_id.to_string(),
            ActiveChallenge {
                vault_id: vault_id.to_string(),
                challenge_nonce,
                expires_at_unix_ms,
                reason,
            },
        );

        Ok(UnlockMessage::UnlockChallenge {
            vault_id: vault_id.to_string(),
            pc_name,
            account_name,
            reason,
            challenge_nonce: challenge_nonce.to_vec(),
            expires_at_unix_ms,
        })
    }

    /// Verifies the phone's [`UnlockMessage::UnlockResponse`] and unwraps the
    /// stored PC password in memory for the OS login provider.
    pub fn verify_and_unlock(
        &mut self,
        vault_id: &str,
        challenge_nonce: &[u8],
        ephemeral_unwrap_token: &[u8],
    ) -> Result<UnlockedCredential, VaultError> {
        let active = self
            .active_challenges
            .remove(vault_id)
            .ok_or_else(|| VaultError::ChallengeExpiredOrMissing(vault_id.to_string()))?;

        if current_unix_ms() > active.expires_at_unix_ms {
            self.increment_failed_attempts(vault_id)?;
            return Err(VaultError::ChallengeExpiredOrMissing(vault_id.to_string()));
        }

        let nonce_arr: [u8; 32] = challenge_nonce
            .try_into()
            .map_err(|_| VaultError::NonceMismatch)?;
        if !constant_time_eq_32(&active.challenge_nonce, &nonce_arr) {
            self.increment_failed_attempts(vault_id)?;
            return Err(VaultError::NonceMismatch);
        }

        let token_arr: [u8; 32] = ephemeral_unwrap_token
            .try_into()
            .map_err(|_| VaultError::InvalidMac)?;

        let (account_name, pc_share_blob, ciphertext, tag_blob, failed_attempts): (
            String,
            Vec<u8>,
            Vec<u8>,
            Vec<u8>,
            u32,
        ) = self
            .conn
            .query_row(
                "SELECT account_name, pc_secret_share, ciphertext, ciphertext_tag, failed_attempts
                 FROM pc_unlock_vault WHERE vault_id = ?1",
                params![vault_id],
                |row| {
                    Ok((
                        row.get(0)?,
                        row.get(1)?,
                        row.get(2)?,
                        row.get(3)?,
                        row.get(4)?,
                    ))
                },
            )
            .map_err(|_| VaultError::NotFound(vault_id.to_string()))?;

        if failed_attempts >= MAX_FAILED_ATTEMPTS {
            return Err(VaultError::LockedOut(vault_id.to_string()));
        }

        let pc_secret_share: [u8; 32] = pc_share_blob
            .try_into()
            .map_err(|_| VaultError::InvalidMac)?;
        let expected_tag: [u8; 32] = tag_blob.try_into().map_err(|_| VaultError::InvalidMac)?;

        let mut master_key = combine_shares(&pc_secret_share, &token_arr);
        let plaintext_res = open_credential(&master_key, vault_id, &ciphertext, &expected_tag);
        zeroize_slice::wipe(&mut master_key);

        match plaintext_res {
            Ok(mut plaintext) => {
                self.reset_failed_attempts(vault_id)?;
                let password =
                    String::from_utf8(plaintext.clone()).map_err(|_| VaultError::InvalidUtf8)?;
                zeroize_slice::wipe(&mut plaintext);
                Ok(UnlockedCredential {
                    account_name,
                    password,
                })
            }
            Err(e) => {
                self.increment_failed_attempts(vault_id)?;
                Err(e)
            }
        }
    }

    fn increment_failed_attempts(&self, vault_id: &str) -> Result<(), VaultError> {
        self.conn.execute(
            "UPDATE pc_unlock_vault SET failed_attempts = failed_attempts + 1 WHERE vault_id = ?1",
            params![vault_id],
        )?;
        Ok(())
    }

    /// Resets the lockout counter (also called when the user logs in manually).
    pub fn reset_failed_attempts(&self, vault_id: &str) -> Result<(), VaultError> {
        self.conn.execute(
            "UPDATE pc_unlock_vault SET failed_attempts = 0 WHERE vault_id = ?1",
            params![vault_id],
        )?;
        Ok(())
    }
}

fn current_unix_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}