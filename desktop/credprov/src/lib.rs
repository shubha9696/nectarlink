// SPDX-License-Identifier: GPL-3.0-or-later
//! Desktop OS Lock-Screen Integration (`desktop/credprov/src/lib.rs`).
//!
//! Provides two native OS authentication entry points that communicate with
//! the background `nectarlink` daemon over a local IPC channel:
//! 1. **Windows 10/11 Credential Provider (`#[cfg(windows)]`)**:
//!    - Connects to `\\.\pipe\NectarLinkUnlockV0` from `LogonUI.exe`
//!    - Packages the unlocked username + password into a
//!      `KERB_INTERACTIVE_UNLOCK_LOGON` buffer for `Winlogon` / `lsass.exe`.
//! 2. **Linux PAM Module (`#[cfg(target_os = "linux")]`)**:
//!    - Connects to `/run/nectarlink/unlock.sock` (or user runtime socket)
//!    - Implements `pam_sm_authenticate` so `sudo`, `polkit`, GDM, SDDM, and
//!      LightDM can authenticate with a fingerprint tap on the phone.

#![warn(missing_debug_implementations)]

use std::io::{Read, Write};
use std::time::Duration;
use serde::{Deserialize, Serialize};

/// Named Pipe path used between `LogonUI.exe` and the NectarLink Windows Service.
pub const WINDOWS_NAMED_PIPE: &str = r"\\.\pipe\NectarLinkUnlockV0";

/// Default Unix domain socket path used between `pam_nectarlink.so` and the daemon.
pub const LINUX_SOCKET_PATH: &str = "/run/nectarlink/unlock.sock";

/// Local IPC request sent from the OS lock screen (`LogonUI.exe` or `PAM`) to
/// the local `nectarlink` daemon running on the same machine.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LocalIpcUnlockRequest {
    pub account_name: String,
    /// `"lock_screen"`, `"uac_elevation"`, or `"privilege_elevation"`.
    pub reason: String,
    pub timeout_ms: u64,
}

/// Local IPC response returned by the local `nectarlink` daemon to the OS login module.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LocalIpcUnlockReply {
    pub approved: bool,
    pub account_name: String,
    /// Populated ONLY for Windows Credential Provider where `Winlogon` requires
    /// a `KERB_INTERACTIVE_UNLOCK_LOGON` serialization; omitted for PAM when
    /// challenge-response alone suffices.
    pub password: Option<String>,
    pub error_message: Option<String>,
}

impl LocalIpcUnlockRequest {
    /// Serializes a request frame as 4-byte BE length + CBOR.
    pub fn to_frame(&self) -> Result<Vec<u8>, String> {
        let mut body = Vec::new();
        ciborium::into_writer(self, &mut body).map_err(|e| e.to_string())?;
        let len = u32::try_from(body.len()).map_err(|e| e.to_string())?;
        let mut frame = Vec::with_capacity(4 + body.len());
        frame.extend_from_slice(&len.to_be_bytes());
        frame.extend_from_slice(&body);
        Ok(frame)
    }
}

impl LocalIpcUnlockReply {
    /// Reads a 4-byte BE length + CBOR reply from a stream.
    pub fn read_from_stream<R: Read>(mut reader: R) -> Result<Self, String> {
        let mut header = [0u8; 4];
        reader
            .read_exact(&mut header)
            .map_err(|e| format!("failed to read IPC header: {e}"))?;
        let len = u32::from_be_bytes(header) as usize;
        if len > 16 * 1024 {
            return Err(format!("IPC reply too large: {len} bytes"));
        }
        let mut body = vec![0u8; len];
        reader
            .read_exact(&mut body)
            .map_err(|e| format!("failed to read IPC body: {e}"))?;
        ciborium::from_reader(body.as_slice()).map_err(|e| format!("invalid IPC CBOR: {e}"))
    }
}

/// Requests a biometric unlock over the platform's local IPC transport.
pub fn request_biometric_unlock_blocking(
    account_name: &str,
    reason: &str,
    timeout: Duration,
) -> Result<LocalIpcUnlockReply, String> {
    let req = LocalIpcUnlockRequest {
        account_name: account_name.to_string(),
        reason: reason.to_string(),
        timeout_ms: timeout.as_millis() as u64,
    };
    let frame = req.to_frame()?;

    #[cfg(unix)]
    {
        use std::os::unix::net::UnixStream;
        let sock_path =
            std::env::var("NECTARLINK_UNLOCK_SOCK").unwrap_or_else(|_| LINUX_SOCKET_PATH.into());
        let mut stream = UnixStream::connect(&sock_path)
            .map_err(|e| format!("failed to connect to {sock_path}: {e}"))?;
        stream
            .set_read_timeout(Some(timeout))
            .map_err(|e| e.to_string())?;
        stream
            .set_write_timeout(Some(timeout))
            .map_err(|e| e.to_string())?;
        stream
            .write_all(&frame)
            .map_err(|e| format!("failed to write to socket: {e}"))?;
        stream.flush().map_err(|e| e.to_string())?;
        LocalIpcUnlockReply::read_from_stream(stream)
    }

    #[cfg(windows)]
    {
        use std::fs::OpenOptions;
        let mut pipe = OpenOptions::new()
            .read(true)
            .write(true)
            .open(WINDOWS_NAMED_PIPE)
            .map_err(|e| format!("failed to open pipe {WINDOWS_NAMED_PIPE}: {e}"))?;
        pipe.write_all(&frame)
            .map_err(|e| format!("failed to write to pipe: {e}"))?;
        pipe.flush().map_err(|e| e.to_string())?;
        LocalIpcUnlockReply::read_from_stream(pipe)
    }
}

/// Windows Credential Provider helper that packs `(domain, username, password)`
/// into a self-relative `KERB_INTERACTIVE_UNLOCK_LOGON` contiguous buffer
/// expected by `ICredentialProviderCredential::GetSerialization`.
pub mod windows_cred_serialization {
    /// Encodes a string as UTF-16LE bytes and returns `(byte_offset, byte_length)`.
    pub fn pack_utf16_field(
        buffer: &mut Vec<u8>,
        value: &str,
    ) -> (u32, u16) {
        let offset = buffer.len() as u32;
        let utf16: Vec<u16> = value.encode_utf16().collect();
        let byte_len = (utf16.len() * 2) as u16;
        for unit in utf16 {
            buffer.extend_from_slice(&unit.to_le_bytes());
        }
        // Null terminator (not counted in UNICODE_STRING.Length, included in MaximumLength)
        buffer.extend_from_slice(&0u16.to_le_bytes());
        (offset, byte_len)
    }
}

/// Linux PAM C ABI entry points (`pam_sm_authenticate` & `pam_sm_setcred`).
/// When compiled as a `cdylib` (`libpam_nectarlink.so`) and added to
/// `/etc/pam.d/sudo` or `/etc/pam.d/gdm-password` as:
///   `auth sufficient libpam_nectarlink.so`
/// any `sudo` or desktop login triggers a fingerprint prompt on the paired phone.
#[cfg(target_os = "linux")]
pub mod linux_pam {
    use super::request_biometric_unlock_blocking;
    use std::ffi::{c_char, c_int, c_void, CStr};
    use std::time::Duration;

    pub const PAM_SUCCESS: c_int = 0;
    pub const PAM_AUTH_ERR: c_int = 7;
    pub const PAM_USER_UNKNOWN: c_int = 10;

    #[link(name = "pam")]
    unsafe extern "C" {
        fn pam_get_user(
            pamh: *const c_void,
            user: *mut *const c_char,
            prompt: *const c_char,
        ) -> c_int;
    }

    #[unsafe(no_mangle)]
    pub unsafe extern "C" fn pam_sm_authenticate(
        pamh: *const c_void,
        _flags: c_int,
        _argc: c_int,
        _argv: *const *const c_char,
    ) -> c_int {
        let mut user_ptr: *const c_char = std::ptr::null();
        let rc = unsafe { pam_get_user(pamh, &mut user_ptr, std::ptr::null()) };
        if rc != PAM_SUCCESS || user_ptr.is_null() {
            return PAM_USER_UNKNOWN;
        }
        let Ok(username) = unsafe { CStr::from_ptr(user_ptr) }.to_str() else {
            return PAM_USER_UNKNOWN;
        };

        match request_biometric_unlock_blocking(
            username,
            "privilege_elevation",
            Duration::from_secs(30),
        ) {
            Ok(reply) if reply.approved => PAM_SUCCESS,
            _ => PAM_AUTH_ERR,
        }
    }

    #[unsafe(no_mangle)]
    pub extern "C" fn pam_sm_setcred(
        _pamh: *const c_void,
        _flags: c_int,
        _argc: c_int,
        _argv: *const *const c_char,
    ) -> c_int {
        PAM_SUCCESS
    }
}