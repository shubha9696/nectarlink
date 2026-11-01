// SPDX-License-Identifier: MPL-2.0
//! Protocol v0 messages and cryptographic framing for unlocking a paired PC
//! with the phone's biometric sensor (`core/nectarlink-protocol/src/unlock.rs`).
//!
//! Uses length-prefixed CBOR (`ciborium`) over `iroh` QUIC streams and requires
//! zero dependencies outside NectarLink's workspace `Cargo.toml`.

#![forbid(unsafe_code)]
#![warn(missing_debug_implementations)]

use std::io::Cursor;
use serde::{Deserialize, Serialize};
use thiserror::Error;

/// Capability identifier advertised in NectarLink's capability matrix.
pub const CAPABILITY_PC_UNLOCK: &str = "pc.unlock.v0";

/// Maximum framed CBOR payload size for an unlock control message (16 KiB).
pub const MAX_UNLOCK_FRAME_BYTES: usize = 16 * 1024;

/// Challenge validity window in milliseconds (30 seconds).
pub const CHALLENGE_TTL_MS: u64 = 30_000;

/// Errors returned while encoding, decoding, or validating `pc.unlock.v0` frames.
#[derive(Debug, Error)]
pub enum UnlockProtocolError {
    #[error("unlock frame length {0} exceeds maximum {MAX_UNLOCK_FRAME_BYTES} bytes")]
    FrameTooLarge(usize),
    #[error("truncated length prefix (expected 4-byte big-endian u32)")]
    TruncatedHeader,
    #[error("CBOR serialization failed: {0}")]
    Encode(String),
    #[error("CBOR deserialization failed: {0}")]
    Decode(String),
}

/// Context describing why the PC is requesting biometric authentication.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum UnlockReason {
    /// Windows `LogonUI.exe` / Linux GDM, SDDM, or LightDM lock screen.
    LockScreen,
    /// Windows UAC elevation prompt (`credprov` in consent UI).
    UacElevation,
    /// Linux PAM `sudo` or `polkit` (`pkexec`) authentication prompt.
    PrivilegeElevation,
}

/// Top-level wire envelope for the `pc.unlock.v0` QUIC bidirectional stream.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum UnlockMessage {
    /// Step 1 of enrollment (PC -> Phone): PC sends the generated 32-byte
    /// `phone_key_share` to be sealed inside the Android Keystore behind
    /// `BiometricPrompt` (`setUserAuthenticationRequired(true)`).
    ///
    /// The user's PC password NEVER leaves the PC.
    EnrollRequest {
        /// Unique identifier for this enrolled vault slot on the PC.
        vault_id: String,
        /// Human-readable PC hostname (e.g. `"DESKTOP-BEE"`).
        pc_name: String,
        /// OS account name being enrolled (e.g. `"bee"` or `"DOMAIN\\bee"`).
        account_name: String,
        /// 32-byte random key share ($K_{\text{phone}}$) generated on the PC.
        #[serde(with = "serde_bytes")]
        phone_key_share: Vec<u8>,
    },

    /// Step 2 of enrollment (Phone -> PC): Phone confirms that `phone_key_share`
    /// has been wrapped by a hardware-backed Android Keystore key after an
    /// initial fingerprint scan, and proves possession via an HMAC tag.
    EnrollAck {
        vault_id: String,
        /// `BLAKE3_keyed(derive_enroll_key(phone_key_share), vault_id)`.
        #[serde(with = "serde_bytes")]
        enrollment_proof: Vec<u8>,
    },

    /// Runtime unlock request (PC -> Phone): Sent when the PC lock screen or
    /// `sudo`/UAC prompt needs the phone's key share to unwrap the local vault.
    UnlockChallenge {
        vault_id: String,
        pc_name: String,
        account_name: String,
        reason: UnlockReason,
        /// 32-byte single-use random challenge nonce ($N$).
        #[serde(with = "serde_bytes")]
        challenge_nonce: Vec<u8>,
        /// Monotonic Unix timestamp in milliseconds when the challenge expires.
        expires_at_unix_ms: u64,
    },

    /// Runtime unlock response (Phone -> PC): Produced ONLY after the user
    /// touches the fingerprint sensor and Android Keystore decrypts `phone_key_share`.
    UnlockResponse {
        vault_id: String,
        /// Echoed 32-byte challenge nonce ($N$).
        #[serde(with = "serde_bytes")]
        challenge_nonce: Vec<u8>,
        /// Ephemeral unwrapping token:
        /// `BLAKE3_derive("nectarlink.pc-unlock.v0.unwrap", K_phone || N)`.
        /// Together with the PC's local DPAPI-protected salt, this reconstructs
        /// the one-time key needed to decrypt the local PC vault entry.
        #[serde(with = "serde_bytes")]
        ephemeral_unwrap_token: Vec<u8>,
        /// `HMAC-SHA256(K_phone, vault_id || N || ephemeral_unwrap_token)`.
        #[serde(with = "serde_bytes")]
        response_mac: Vec<u8>,
    },

    /// Explicit rejection or cancellation from the phone user.
    UnlockDenied {
        vault_id: String,
        #[serde(with = "serde_bytes")]
        challenge_nonce: Vec<u8>,
        reason: String,
    },

    /// Revokes an enrolled vault slot from either side.
    RevokeEnrollment {
        vault_id: String,
    },
}

impl UnlockMessage {
    /// Encodes this message as a 4-byte big-endian length prefix followed by
    /// canonical CBOR bytes (`ciborium`), matching `docs/protocol/v0.md`.
    pub fn to_framed_cbor(&self) -> Result<Vec<u8>, UnlockProtocolError> {
        let mut payload = Vec::with_capacity(256);
        ciborium::into_writer(self, &mut payload)
            .map_err(|e| UnlockProtocolError::Encode(e.to_string()))?;

        if payload.len() > MAX_UNLOCK_FRAME_BYTES {
            return Err(UnlockProtocolError::FrameTooLarge(payload.len()));
        }

        let len_u32 = u32::try_from(payload.len())
            .map_err(|_| UnlockProtocolError::FrameTooLarge(payload.len()))?;
        let mut frame = Vec::with_capacity(4 + payload.len());
        frame.extend_from_slice(&len_u32.to_be_bytes());
        frame.extend_from_slice(&payload);
        Ok(frame)
    }

    /// Decodes a length-prefixed CBOR frame into an [`UnlockMessage`].
    pub fn from_framed_cbor(frame: &[u8]) -> Result<Self, UnlockProtocolError> {
        if frame.len() < 4 {
            return Err(UnlockProtocolError::TruncatedHeader);
        }
        let mut len_bytes = [0u8; 4];
        len_bytes.copy_from_slice(&frame[..4]);
        let declared_len = u32::from_be_bytes(len_bytes) as usize;

        if declared_len > MAX_UNLOCK_FRAME_BYTES {
            return Err(UnlockProtocolError::FrameTooLarge(declared_len));
        }
        let body = &frame[4..];
        if body.len() != declared_len {
            return Err(UnlockProtocolError::Decode(format!(
                "frame body length {} does not match header {}",
                body.len(),
                declared_len
            )));
        }

        ciborium::from_reader(Cursor::new(body))
            .map_err(|e| UnlockProtocolError::Decode(e.to_string()))
    }
}

/// Derives the 32-byte enrollment confirmation proof using BLAKE3's keyed hash.
pub fn compute_enrollment_proof(phone_key_share: &[u8; 32], vault_id: &str) -> [u8; 32] {
    let subkey = blake3::derive_key("nectarlink.pc-unlock.v0.enroll-proof", phone_key_share);
    *blake3::keyed_hash(&subkey, vault_id.as_bytes()).as_bytes()
}

/// Derives the 32-byte `ephemeral_unwrap_token` and `response_mac` on the phone
/// once the Android Keystore releases `phone_key_share` after biometric auth.
///
/// Crucially, `ephemeral_unwrap_token` is deterministic across enrollment and
/// unlock for a static `vault_binding_id`, while `response_mac` binds the
/// single-use `challenge_nonce` so network replays are rejected.
pub fn compute_phone_unlock_tokens(
    phone_key_share: &[u8; 32],
    vault_id: &str,
    challenge_nonce: &[u8; 32],
) -> ([u8; 32], [u8; 32]) {
    let mut binding_input = Vec::with_capacity(32 + vault_id.len());
    binding_input.extend_from_slice(phone_key_share);
    binding_input.extend_from_slice(vault_id.as_bytes());

    let unwrap_token = blake3::derive_key("nectarlink.pc-unlock.v0.unwrap-token", &binding_input);

    let mac_key = blake3::derive_key("nectarlink.pc-unlock.v0.response-mac", phone_key_share);
    let mut mac_hasher = blake3::Hasher::new_keyed(&mac_key);
    mac_hasher.update(vault_id.as_bytes());
    mac_hasher.update(challenge_nonce);
    mac_hasher.update(&unwrap_token);
    let response_mac = *mac_hasher.finalize().as_bytes();

    (unwrap_token, response_mac)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_cbor_framing() {
        let msg = UnlockMessage::UnlockChallenge {
            vault_id: "vault-01".to_string(),
            pc_name: "WORKSTATION".to_string(),
            account_name: "bee".to_string(),
            reason: UnlockReason::LockScreen,
            challenge_nonce: vec![0x42; 32],
            expires_at_unix_ms: 1_793_550_000_000,
        };
        let bytes = msg.to_framed_cbor().expect("encode");
        let decoded = UnlockMessage::from_framed_cbor(&bytes).expect("decode");
        assert_eq!(msg, decoded);
    }

    #[test]
    fn deterministic_phone_tokens_and_proof() {
        let share = [0xAB; 32];
        let nonce = [0xCD; 32];
        let proof = compute_enrollment_proof(&share, "vault-01");
        assert_eq!(proof, compute_enrollment_proof(&share, "vault-01"));
        assert_ne!(proof, compute_enrollment_proof(&share, "vault-02"));

        let (t1, m1) = compute_phone_unlock_tokens(&share, "vault-01", &nonce);
        let (t2, m2) = compute_phone_unlock_tokens(&share, "vault-01", &[0xEE; 32]);
        assert_eq!(t1, t2, "unwrap token must depend on share + vault_id");
        assert_ne!(m1, m2, "MAC must change with every challenge nonce");
    }
}