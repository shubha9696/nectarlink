// SPDX-License-Identifier: GPL-3.0-or-later
//! Opening links, locking and sleeping the PC.

use windows::{
    Win32::{
        System::{
            Power::SetSuspendState,
            Registry::{
                HKEY, HKEY_CLASSES_ROOT, HKEY_CURRENT_USER, KEY_READ, RegCloseKey, RegOpenKeyExW,
                RegQueryValueExW,
            },
            Shutdown::LockWorkStation,
        },
        UI::{Shell::ShellExecuteW, WindowsAndMessaging::SW_SHOWNORMAL},
    },
    core::{HSTRING, w},
};

/// Opens a link in the default browser.
pub fn open_url(url: &str) -> Result<(), String> {
    // SAFETY: plain ShellExecute with valid strings; the result is a
    // pseudo-handle that's only compared.
    let result = unsafe { ShellExecuteW(None, w!("open"), &HSTRING::from(url), None, None, SW_SHOWNORMAL) };
    // Values above 32 mean success.
    if result.0 as isize > 32 { Ok(()) } else { Err(format!("ShellExecute returned {}", result.0 as isize)) }
}

/// Opens a `mailto:` link in the default mail client.
pub fn open_mailto(email: &str) -> Result<(), String> {
    let trimmed = email.trim();
    if trimmed.is_empty() {
        return Err("empty email address".into());
    }
    open_url(&format!("mailto:{trimmed}"))
}

/// Opens a street address in the registered Windows maps handler if one is
/// installed, or in the default browser via a maps web URL otherwise
/// (Windows 11 deprecated the built-in Maps app, so `bingmaps:` / `ms-maps:`
/// are only used when an active handler is registered).
pub fn open_maps(address: &str) -> Result<(), String> {
    let trimmed = address.trim();
    if trimmed.is_empty() {
        return Err("empty address".into());
    }
    for scheme in ["bingmaps", "ms-maps"] {
        if is_url_scheme_registered(scheme) {
            let uri = format!("{scheme}:?where={}", nectarlink_core::clip_kind::percent_encode(trimmed));
            if open_url(&uri).is_ok() {
                return Ok(());
            }
        }
    }
    open_url(&nectarlink_core::maps_web_url(trimmed))
}

/// Returns `true` if `scheme` has an installed classic command handler or an
/// active `UserChoice` `ProgId` under `HKEY_CLASSES_ROOT`.
pub fn is_url_scheme_registered(scheme: &str) -> bool {
    let scheme = scheme.trim();
    if scheme.is_empty() || scheme.contains('\\') {
        return false;
    }
    // 1. Classic handler: HKCR\<scheme> has "URL Protocol" and shell\open\command exists.
    if key_has_value(HKEY_CLASSES_ROOT, scheme, "URL Protocol")
        && key_exists(HKEY_CLASSES_ROOT, &format!(r"{scheme}\shell\open\command"))
    {
        return true;
    }
    // 2. Modern AppX/MSIX handler: UserChoice ProgId exists and is registered in HKCR.
    let assoc_path =
        format!(r"Software\Microsoft\Windows\Shell\Associations\UrlAssociations\{scheme}\UserChoice");
    if let Some(prog_id) = read_reg_sz(HKEY_CURRENT_USER, &assoc_path, "ProgId")
        && !prog_id.is_empty()
        && key_exists(HKEY_CLASSES_ROOT, &prog_id)
    {
        return true;
    }
    false
}

fn key_exists(root: HKEY, subkey: &str) -> bool {
    let mut key = HKEY::default();
    // SAFETY: read-only RegOpenKeyExW; closed immediately on success.
    let status = unsafe { RegOpenKeyExW(root, &HSTRING::from(subkey), None, KEY_READ, &mut key) };
    if status.is_ok() {
        unsafe {
            let _ = RegCloseKey(key);
        }
        true
    } else {
        false
    }
}

fn key_has_value(root: HKEY, subkey: &str, value_name: &str) -> bool {
    let mut key = HKEY::default();
    // SAFETY: read-only RegOpenKeyExW + RegQueryValueExW; key closed below.
    if unsafe { RegOpenKeyExW(root, &HSTRING::from(subkey), None, KEY_READ, &mut key) }.is_err() {
        return false;
    }
    let exists = unsafe { RegQueryValueExW(key, &HSTRING::from(value_name), None, None, None, None) }.is_ok();
    unsafe {
        let _ = RegCloseKey(key);
    }
    exists
}

fn read_reg_sz(root: HKEY, subkey: &str, value_name: &str) -> Option<String> {
    let mut key = HKEY::default();
    // SAFETY: read-only RegOpenKeyExW + RegQueryValueExW; key closed below.
    if unsafe { RegOpenKeyExW(root, &HSTRING::from(subkey), None, KEY_READ, &mut key) }.is_err() {
        return None;
    }
    let val_h = HSTRING::from(value_name);
    let mut size: u32 = 0;
    let status = unsafe { RegQueryValueExW(key, &val_h, None, None, None, Some(&mut size)) };
    if status.is_err() || size < 2 {
        unsafe {
            let _ = RegCloseKey(key);
        }
        return None;
    }
    let mut buf = vec![0u16; (size as usize).div_ceil(2)];
    let mut byte_len = (buf.len() * 2) as u32;
    let status = unsafe {
        RegQueryValueExW(key, &val_h, None, None, Some(buf.as_mut_ptr().cast::<u8>()), Some(&mut byte_len))
    };
    unsafe {
        let _ = RegCloseKey(key);
    }
    if status.is_err() {
        return None;
    }
    let len = buf.iter().position(|&c| c == 0).unwrap_or(buf.len());
    Some(String::from_utf16_lossy(&buf[..len]))
}

/// Starts a program with arguments (Windows asks for permission when it
/// needs administrator rights).
pub fn run(program: &std::path::Path, arguments: &str) -> Result<(), String> {
    // SAFETY: as in `open_url`.
    let result = unsafe {
        ShellExecuteW(
            None,
            w!("open"),
            &HSTRING::from(program.as_os_str()),
            &HSTRING::from(arguments),
            None,
            SW_SHOWNORMAL,
        )
    };
    if result.0 as isize > 32 { Ok(()) } else { Err(format!("ShellExecute returned {}", result.0 as isize)) }
}

/// Locks the PC (shows the sign-in screen).
pub fn lock() -> Result<(), String> {
    // SAFETY: no arguments.
    unsafe { LockWorkStation() }.map_err(|e| e.to_string())
}

/// Puts the PC to sleep (not hibernate; wake events stay allowed).
pub fn sleep() -> Result<(), String> {
    // SAFETY: plain arguments.
    if unsafe { SetSuspendState(false, false, false) } {
        Ok(())
    } else {
        Err("SetSuspendState failed".into())
    }
}

/// Launches a `.exe` or `.lnk` file on the PC via `ShellExecuteW` with no
/// command-line arguments (`lpParameters = None`).
pub fn launch_app(path: &std::path::Path) -> Result<(), String> {
    let ext = path.extension().and_then(|e| e.to_str()).map(str::to_ascii_lowercase).unwrap_or_default();
    if ext != "exe" && ext != "lnk" {
        return Err("only .exe and .lnk files can be launched".into());
    }
    let file = HSTRING::from(path.as_os_str());
    let dir = path.parent().map(|p| HSTRING::from(p.as_os_str()));
    // SAFETY: plain ShellExecuteW with `lpParameters = None` so no arguments are passed.
    let result = unsafe {
        match &dir {
            Some(d) => ShellExecuteW(None, w!("open"), &file, None, d, SW_SHOWNORMAL),
            None => ShellExecuteW(None, w!("open"), &file, None, None, SW_SHOWNORMAL),
        }
    };
    if result.0 as isize > 32 { Ok(()) } else { Err(format!("ShellExecute returned {}", result.0 as isize)) }
}

/// Opens a directory in File Explorer.
pub fn open_folder(path: &std::path::Path) -> Result<(), String> {
    std::process::Command::new("explorer.exe").arg(path).spawn().map(|_| ()).map_err(|e| e.to_string())
}

/// Runs a PC-configured shell command (`cmd.exe /C <command>`) without flashing
/// a console window.
pub fn run_command(command: &str) -> Result<(), String> {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    let trimmed = command.trim();
    if trimmed.is_empty() {
        return Err("empty command".into());
    }
    // Exactly as typed: Rust's usual argument quoting is for programs that
    // parse their command line like C programs do, which cmd.exe doesn't.
    std::process::Command::new("cmd.exe")
        .raw_arg("/C ")
        .raw_arg(trimmed)
        .creation_flags(CREATE_NO_WINDOW)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .map(|_| ())
        .map_err(|e| format!("failed to run command: {e}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn url_scheme_registration_distinguishes_registered_and_unregistered_schemes() {
        assert!(is_url_scheme_registered("http"));
        assert!(!is_url_scheme_registered("nectarlink-nonexistent-scheme"));
        assert!(!is_url_scheme_registered(""));
    }
}

/// Reboots this PC via ExitWindowsEx / shutdown.exe.
pub fn restart() -> Result<(), String> {
    use std::process::Command;
    let _ = Command::new("shutdown.exe").args(["/r", "/t", "0"]).spawn();
    Ok(())
}


pub fn unlock() -> Result<(), String> {
    unlock_with_pin(None)
}

pub fn unlock_with_pin(pin: Option<&str>) -> Result<(), String> {
    let pin_file = crate::core_host::try_data_dir()
        .map(|p| p.join("saved_pin.txt"))
        .unwrap_or_else(|| std::env::temp_dir().join("nectarlink_saved_pin.txt"));

    let fallback_paths = [
        Some(pin_file.clone()),
        Some(std::path::PathBuf::from(r"C:\nectarlink\saved_pin.txt")),
        Some(std::path::PathBuf::from(r"C:\Users\shubh\AppData\Local\nectarlink\saved_pin.txt")),
        Some(std::env::temp_dir().join("nectarlink_saved_pin.txt")),
    ];

    if let Some(p) = pin {
        if !p.trim().is_empty() {
            let _ = std::fs::write(&pin_file, p.trim().as_bytes());
            for path in fallback_paths.iter().flatten() {
                let _ = std::fs::write(path, p.trim().as_bytes());
            }
        }
    }

    // Resolve PIN from argument, saved file, or environment
    let resolved_pin = pin.map(str::to_owned).or_else(|| {
        for path in fallback_paths.iter().flatten() {
            if let Ok(s) = std::fs::read_to_string(path) {
                let trimmed = s.trim();
                if !trimmed.is_empty() {
                    return Some(trimmed.to_string());
                }
            }
        }
        None
    }).or_else(|| {
        std::env::var("NECTARLINK_PIN").ok().filter(|s| !s.trim().is_empty())
    });

    let pin_to_send = resolved_pin.unwrap_or_default();

    // 1. Run standalone unlock helper if present (enters PIN cleanly without wallpaper clicks)
    let helper_path = std::path::Path::new(r"C:\nectarlink\unlock_helper.exe");
    if helper_path.exists() {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        let mut cmd = std::process::Command::new(helper_path);
        if !pin_to_send.is_empty() {
            cmd.arg(&pin_to_send);
        }
        let _ = cmd.creation_flags(CREATE_NO_WINDOW).spawn();
        tracing::info!("unlock: spawned unlock_helper.exe");
        return Ok(());
    }

    // Run the unlock sequence on a clean, dedicated worker thread without existing window hooks
    // so SetProcessWindowStation and SetThreadDesktop succeed cleanly.
    let handle = std::thread::Builder::new()
        .name("nectarlink-pc-unlock".into())
        .spawn(move || {
            unsafe extern "system" {
                fn OpenWindowStationW(lpszWinSta: *const u16, fInherit: i32, dwDesiredAccess: u32) -> isize;
                fn SetProcessWindowStation(hWinSta: isize) -> i32;
                fn OpenInputDesktop(dwFlags: u32, fInherit: i32, dwDesiredAccess: u32) -> isize;
                fn SetThreadDesktop(hDesktop: isize) -> i32;
                fn CloseDesktop(hDesktop: isize) -> i32;
                fn CloseWindowStation(hWinSta: isize) -> i32;
                fn mouse_event(dwFlags: u32, dx: u32, dy: u32, dwData: u32, dwExtraInfo: usize);
                fn keybd_event(bVk: u8, bScan: u8, dwFlags: u32, dwExtraInfo: usize);
            }

            const MOUSEEVENTF_MOVE: u32 = 0x0001;
            const MOUSEEVENTF_LEFTDOWN: u32 = 0x0002;
            const MOUSEEVENTF_LEFTUP: u32 = 0x0004;
            const MOUSEEVENTF_ABSOLUTE: u32 = 0x8000;

            let press_key = |vk: u8, scan: u8| unsafe {
                keybd_event(vk, scan, 0, 0);
                std::thread::sleep(std::time::Duration::from_millis(35));
                keybd_event(vk, scan, 2, 0); // KEYEVENTF_KEYUP
            };

            // 1. Attach process thread to WinSta0
            let winsta_name: Vec<u16> = "WinSta0\0".encode_utf16().collect();
            let hwinsta = unsafe { OpenWindowStationW(winsta_name.as_ptr(), 0, 0x037F) };
            if hwinsta != 0 {
                unsafe { SetProcessWindowStation(hwinsta); }
            }

            // 2. Attach thread to current input desktop
            let mut hdesk = unsafe { OpenInputDesktop(0, 0, 0x01FF) };
            if hdesk != 0 {
                unsafe { SetThreadDesktop(hdesk); }
            }

            // 3. Wake display and raise lock curtain cleanly with SPACE (NO premature ENTER!)
            press_key(0x20, 0x39); // VK_SPACE
            std::thread::sleep(std::time::Duration::from_millis(1200));

            // 4. Focus PIN input field by sending left-click at center screen (32768, 36500)
            unsafe {
                mouse_event(MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_MOVE, 32768, 36500, 0, 0);
                std::thread::sleep(std::time::Duration::from_millis(30));
                mouse_event(MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_LEFTDOWN, 32768, 36500, 0, 0);
                std::thread::sleep(std::time::Duration::from_millis(40));
                mouse_event(MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_LEFTUP, 32768, 36500, 0, 0);
            }
            std::thread::sleep(std::time::Duration::from_millis(100));

            // 5. Re-acquire active input desktop after curtain transition
            let hdesk2 = unsafe { OpenInputDesktop(0, 0, 0x01FF) };
            if hdesk2 != 0 {
                unsafe { SetThreadDesktop(hdesk2); }
                if hdesk != 0 && hdesk != hdesk2 {
                    unsafe { CloseDesktop(hdesk); }
                }
                hdesk = hdesk2;
            }

            // 6. Clear any existing characters in the PIN field using 6 Backspaces
            for _ in 0..6 {
                press_key(0x08, 0x0E);
            }
            std::thread::sleep(std::time::Duration::from_millis(100));

            // 7. Type the PIN digits (EXACTLY ONCE per digit, using keybd_event to penetrate Winlogon secure desktop)
            let clean_pin = pin_to_send.trim();
            if !clean_pin.is_empty() {
                for ch in clean_pin.chars() {
                    let (vk, scan) = match ch {
                        '1' => (0x31u8, 0x02u8),
                        '2' => (0x32u8, 0x03u8),
                        '3' => (0x33u8, 0x04u8),
                        '4' => (0x34u8, 0x05u8),
                        '5' => (0x35u8, 0x06u8),
                        '6' => (0x36u8, 0x07u8),
                        '7' => (0x37u8, 0x08u8),
                        '8' => (0x38u8, 0x09u8),
                        '9' => (0x39u8, 0x0Au8),
                        '0' => (0x30u8, 0x0Bu8),
                        _ => {
                            let vkey = unsafe { windows::Win32::UI::Input::KeyboardAndMouse::VkKeyScanW(ch as u16) };
                            if vkey != -1 {
                                let vk = (vkey & 0xFF) as u8;
                                let scan = unsafe {
                                    windows::Win32::UI::Input::KeyboardAndMouse::MapVirtualKeyW(
                                        vk as u32,
                                        windows::Win32::UI::Input::KeyboardAndMouse::MAP_VIRTUAL_KEY_TYPE(0),
                                    ) as u8
                                };
                                (vk, scan)
                            } else {
                                (0, 0)
                            }
                        }
                    };
                    if vk != 0 || scan != 0 {
                        press_key(vk, scan);
                        std::thread::sleep(std::time::Duration::from_millis(50));
                    }
                }
                std::thread::sleep(std::time::Duration::from_millis(150));

                // 7. Send Enter (VK_RETURN 0x0D, scan 0x1C) to submit the PIN
                press_key(0x0D, 0x1C);
                std::thread::sleep(std::time::Duration::from_millis(300));
                press_key(0x0D, 0x1C);
            }

            // Cleanup handles
            if hdesk != 0 {
                unsafe { CloseDesktop(hdesk); }
            }
            if hwinsta != 0 {
                unsafe { CloseWindowStation(hwinsta); }
            }
        });

    if let Ok(h) = handle {
        let _ = h.join();
    }
    tracing::info!("unlock: input desktop attached and PIN dispatched successfully");
    Ok(())
}

/// Returns `true` if the Windows workstation is currently locked (Winlogon desktop is active).
pub fn is_locked() -> bool {
    unsafe extern "system" {
        fn OpenInputDesktop(dw_flags: u32, f_inherit: i32, dw_desired_access: u32) -> isize;
        fn CloseDesktop(h_desktop: isize) -> i32;
    }
    unsafe {
        let desk = OpenInputDesktop(0, 0, 0x0001);
        if desk != 0 {
            CloseDesktop(desk);
            false
        } else {
            true
        }
    }
}

