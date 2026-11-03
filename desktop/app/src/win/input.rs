// SPDX-License-Identifier: GPL-3.0-or-later
//! Synthesizes mouse and keyboard input on Windows (`SendInput`) for a paired
//! phone acting as a touchpad, keyboard or presentation remote
//! (`docs/protocol/remote.md`).

use std::sync::Mutex;

use nectarlink_core::{ButtonAction, KeyMod, MouseButton, PowerAction, SlideAction, TouchAction};
use windows::Win32::UI::{
    Input::KeyboardAndMouse::{
        INPUT, INPUT_0, INPUT_KEYBOARD, INPUT_MOUSE, KEYBD_EVENT_FLAGS, KEYBDINPUT, KEYEVENTF_EXTENDEDKEY,
        KEYEVENTF_KEYUP, KEYEVENTF_UNICODE, MOUSE_EVENT_FLAGS, MOUSEEVENTF_ABSOLUTE, MOUSEEVENTF_HWHEEL,
        MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP, MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP,
        MOUSEEVENTF_MOVE, MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP, MOUSEEVENTF_WHEEL, MOUSEINPUT,
        SendInput, VIRTUAL_KEY, VK_A, VK_B, VK_BACK, VK_C, VK_CONTROL, VK_DELETE, VK_DOWN, VK_END, VK_ESCAPE,
        VK_F1, VK_F5, VK_HOME, VK_INSERT, VK_LEFT, VK_LWIN, VK_MEDIA_NEXT_TRACK, VK_MEDIA_PLAY_PAUSE,
        VK_MEDIA_PREV_TRACK, VK_MENU, VK_NEXT, VK_PRIOR, VK_RETURN, VK_RIGHT, VK_SHIFT, VK_SPACE, VK_TAB,
        VK_UP, VK_V, VK_VOLUME_DOWN, VK_VOLUME_MUTE, VK_VOLUME_UP, VK_X, VK_Y, VK_Z,
    },
    WindowsAndMessaging::WHEEL_DELTA,
};

/// Fractional remainders for sub-pixel pointer motion and sub-notch scrolling,
/// plus which mouse buttons are currently held down by remote input.
#[derive(Default)]
struct InputState {
    move_rem_x: f32,
    move_rem_y: f32,
    scroll_rem_x: f32,
    scroll_rem_y: f32,
    held_left: bool,
    held_right: bool,
    held_middle: bool,
}

static STATE: Mutex<InputState> = Mutex::new(InputState {
    move_rem_x: 0.0,
    move_rem_y: 0.0,
    scroll_rem_x: 0.0,
    scroll_rem_y: 0.0,
    held_left: false,
    held_right: false,
    held_middle: false,
});

/// Attaches the calling thread to the currently active input desktop (Default or Winlogon / Secure Desktop)
/// so that inputs (mouse, touch, keyboard) and GDI capture reach the screen even while locked.
pub fn sync_input_desktop() -> bool {
    thread_local! {
        static CURRENT_DESK: std::cell::Cell<isize> = const { std::cell::Cell::new(0) };
        static CURRENT_NAME: std::cell::RefCell<String> = const { std::cell::RefCell::new(String::new()) };
    }

    unsafe extern "system" {
        fn OpenWindowStationW(lpszWinSta: *const u16, fInherit: i32, dwDesiredAccess: u32) -> isize;
        fn SetProcessWindowStation(hWinSta: isize) -> i32;
        fn OpenInputDesktop(dwFlags: u32, fInherit: i32, dwDesiredAccess: u32) -> isize;
        fn SetThreadDesktop(hDesktop: isize) -> i32;
        fn CloseDesktop(hDesktop: isize) -> i32;
        fn GetUserObjectInformationA(
            hObj: isize,
            nIndex: i32,
            pvInfo: *mut u8,
            nLength: u32,
            lpnLengthNeeded: *mut u32,
        ) -> i32;
    }

    static WINSTA_INIT: std::sync::Once = std::sync::Once::new();
    WINSTA_INIT.call_once(|| unsafe {
        let winsta_name: Vec<u16> = "WinSta0\0".encode_utf16().collect();
        let hwinsta = OpenWindowStationW(winsta_name.as_ptr(), 0, 0x037F);
        if hwinsta != 0 {
            SetProcessWindowStation(hwinsta);
        }
    });

    let mut hdesk = unsafe { OpenInputDesktop(0, 0, 0x01FF) };
    if hdesk == 0 {
        for &access in &[0x0081, 0x0200_0000, 0x0001, 0x0080] {
            hdesk = unsafe { OpenInputDesktop(0, 0, access) };
            if hdesk != 0 {
                break;
            }
        }
    }

    if hdesk == 0 {
        unsafe extern "system" {
            fn OpenDesktopW(lpszDesktop: *const u16, dwFlags: u32, fInherit: i32, dwDesiredAccess: u32) -> isize;
        }
        let winlogon: Vec<u16> = "Winlogon\0".encode_utf16().collect();
        hdesk = unsafe { OpenDesktopW(winlogon.as_ptr(), 0, 0, 0x01FF) };
        if hdesk == 0 {
            hdesk = unsafe { OpenDesktopW(winlogon.as_ptr(), 0, 0, 0x0001 | 0x0080 | 0x0040) };
        }
    }

    if hdesk == 0 {
        return false;
    }

    let mut buf = [0u8; 128];
    let mut needed = 0u32;
    let ok = unsafe {
        GetUserObjectInformationA(hdesk, 2 /* UOI_NAME */, buf.as_mut_ptr(), buf.len() as u32, &mut needed)
    };
    let new_name = if ok != 0 {
        let len = buf.iter().position(|&b| b == 0).unwrap_or(buf.len());
        String::from_utf8_lossy(&buf[..len]).to_string()
    } else {
        String::new()
    };

    let mut changed = false;
    CURRENT_NAME.with(|cur| {
        let mut cur_borrow = cur.borrow_mut();
        if *cur_borrow != new_name {
            if unsafe { SetThreadDesktop(hdesk) } != 0 {
                CURRENT_DESK.with(|d| {
                    let old = d.replace(hdesk);
                    if old != 0 && old != hdesk {
                        unsafe { CloseDesktop(old); }
                    }
                });
                *cur_borrow = new_name;
                changed = true;
            } else {
                unsafe { CloseDesktop(hdesk); }
            }
        } else {
            unsafe { CloseDesktop(hdesk); }
        }
    });

    changed
}

/// Returns `true` if the screen is currently locked / on a secure desktop (e.g. Winlogon, UAC, or lock screen),
/// where standard `SendInput` from standard integrity processes is isolated by UIPI to the inactive background desktop.
pub fn is_screen_locked() -> bool {
    unsafe extern "system" {
        fn OpenInputDesktop(dwFlags: u32, fInherit: i32, dwDesiredAccess: u32) -> isize;
        fn CloseDesktop(hDesktop: isize) -> i32;
        fn GetUserObjectInformationA(
            hObj: isize,
            nIndex: i32,
            pvInfo: *mut u8,
            nLength: u32,
            lpnLengthNeeded: *mut u32,
        ) -> i32;
    }

    let hdesk = unsafe { OpenInputDesktop(0, 0, 0x01FF) };
    if hdesk == 0 {
        // Access denied or cannot open input desktop -> secure desktop (Winlogon / UAC) is active!
        return true;
    }
    let mut buf = [0u8; 128];
    let mut needed = 0u32;
    let ok = unsafe {
        GetUserObjectInformationA(hdesk, 2 /* UOI_NAME */, buf.as_mut_ptr(), buf.len() as u32, &mut needed)
    };
    let locked = if ok != 0 {
        let len = buf.iter().position(|&b| b == 0).unwrap_or(buf.len());
        let name = String::from_utf8_lossy(&buf[..len]);
        name.to_lowercase() != "default"
    } else {
        true
    };
    unsafe { CloseDesktop(hdesk); }
    locked
}

pub fn send_to_lock_pipe(cmd: &str) -> bool {
    use std::fs::OpenOptions;
    use std::io::{BufRead, BufReader, Write};
    for attempt in 0..2 {
        if let Ok(file) = OpenOptions::new().read(true).write(true).open(r"\\.\pipe\nectarlink_lock_input") {
            let mut writer = std::io::BufWriter::new(&file);
            if writer.write_all(cmd.as_bytes()).is_ok() && writer.flush().is_ok() {
                let mut reader = BufReader::new(&file);
                let mut reply = String::new();
                let _ = reader.read_line(&mut reply);
                return true;
            }
        }
        if attempt == 0 {
            use std::os::windows::process::CommandExt;
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            let _ = std::process::Command::new("schtasks")
                .args(["/Run", "/TN", "NectarLinkHelper"])
                .creation_flags(CREATE_NO_WINDOW)
                .status();
            std::thread::sleep(std::time::Duration::from_millis(60));
        }
    }
    false
}

fn send_inputs(inputs: &[INPUT]) {
    if inputs.is_empty() {
        return;
    }

    unsafe extern "system" {
        fn keybd_event(bVk: u8, bScan: u8, dwFlags: u32, dwExtraInfo: usize);
        fn mouse_event(dwFlags: u32, dx: u32, dy: u32, dwData: u32, dwExtraInfo: usize);
    }

    let locked = is_screen_locked();
    if locked {
        for input in inputs {
            match input.r#type {
                INPUT_KEYBOARD => {
                    let ki = unsafe { input.Anonymous.ki };
                    let msg = format!("KEY {} {} {}\n", ki.wVk.0, ki.wScan, ki.dwFlags.0);
                    if !send_to_lock_pipe(&msg) {
                        unsafe {
                            keybd_event(ki.wVk.0 as u8, ki.wScan as u8, ki.dwFlags.0, ki.dwExtraInfo);
                        }
                    }
                }
                INPUT_MOUSE => {
                    let mi = unsafe { input.Anonymous.mi };
                    let move_flags = mi.dwFlags.0 & (MOUSEEVENTF_MOVE.0 | MOUSEEVENTF_ABSOLUTE.0);
                    if move_flags != 0 {
                        let msg = format!("MOUSE {} {} {} 0\n", move_flags, mi.dx, mi.dy);
                        if !send_to_lock_pipe(&msg) {
                            unsafe {
                                mouse_event(move_flags, mi.dx as u32, mi.dy as u32, 0, 0);
                            }
                        }
                    }
                    let btn_flags = mi.dwFlags.0 & !(MOUSEEVENTF_MOVE.0 | MOUSEEVENTF_ABSOLUTE.0);
                    if btn_flags != 0 {
                        let msg = format!("MOUSE {} {} {} {}\n", btn_flags, mi.dx, mi.dy, mi.mouseData);
                        if !send_to_lock_pipe(&msg) {
                            unsafe {
                                mouse_event(btn_flags, mi.dx as u32, mi.dy as u32, mi.mouseData, mi.dwExtraInfo);
                            }
                        }
                    }
                }
                _ => {}
            }
        }
        return;
    }

    sync_input_desktop();
    // SAFETY: `inputs` is a valid slice of `INPUT` structs.
    let sent = unsafe { SendInput(inputs, std::mem::size_of::<INPUT>() as i32) };
    if sent == 0 {
        // Fallback for secure desktop / lock screen where UIPI blocks SendInput
        for input in inputs {
            match input.r#type {
                INPUT_KEYBOARD => unsafe {
                    let ki = input.Anonymous.ki;
                    keybd_event(ki.wVk.0 as u8, ki.wScan as u8, ki.dwFlags.0, ki.dwExtraInfo);
                },
                INPUT_MOUSE => unsafe {
                    let mi = input.Anonymous.mi;
                    mouse_event(mi.dwFlags.0, mi.dx as u32, mi.dy as u32, mi.mouseData, mi.dwExtraInfo);
                },
                _ => {}
            }
        }
    }
}

fn mouse_input(dx: i32, dy: i32, mouse_data: i32, flags: MOUSE_EVENT_FLAGS) -> INPUT {
    INPUT {
        r#type: INPUT_MOUSE,
        Anonymous: INPUT_0 {
            mi: MOUSEINPUT { dx, dy, mouseData: mouse_data as u32, dwFlags: flags, time: 0, dwExtraInfo: 0 },
        },
    }
}

fn vk_input(vk: VIRTUAL_KEY, extended: bool, up: bool) -> INPUT {
    let mut flags = KEYBD_EVENT_FLAGS(0);
    if extended {
        flags |= KEYEVENTF_EXTENDEDKEY;
    }
    if up {
        flags |= KEYEVENTF_KEYUP;
    }
    let scan = unsafe {
        windows::Win32::UI::Input::KeyboardAndMouse::MapVirtualKeyW(vk.0 as u32, windows::Win32::UI::Input::KeyboardAndMouse::MAP_VIRTUAL_KEY_TYPE(0)) as u16
    };
    INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 { ki: KEYBDINPUT { wVk: vk, wScan: scan, dwFlags: flags, time: 0, dwExtraInfo: 0 } },
    }
}

fn unicode_input(unit: u16, up: bool) -> INPUT {
    let mut flags = KEYEVENTF_UNICODE;
    if up {
        flags |= KEYEVENTF_KEYUP;
    }
    INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 {
            ki: KEYBDINPUT { wVk: VIRTUAL_KEY(0), wScan: unit, dwFlags: flags, time: 0, dwExtraInfo: 0 },
        },
    }
}

/// Moves the mouse cursor relatively by `(dx, dy)` pixels, keeping fractions
/// for the next move. Windows applies the user's pointer speed (and "enhance
/// pointer precision") to relative motion itself.
pub fn move_relative(dx: f32, dy: f32) {
    if !dx.is_finite() || !dy.is_finite() {
        return;
    }
    let (ix, iy) = {
        let mut s = STATE.lock().unwrap_or_else(|e| e.into_inner());
        let total_x = s.move_rem_x + dx;
        let total_y = s.move_rem_y + dy;
        let ix = total_x.round() as i32;
        let iy = total_y.round() as i32;
        s.move_rem_x = total_x - (ix as f32);
        s.move_rem_y = total_y - (iy as f32);
        (ix, iy)
    };
    if ix != 0 || iy != 0 {
        send_inputs(&[mouse_input(ix, iy, 0, MOUSEEVENTF_MOVE)]);
    }
}

/// Moves the mouse cursor to normalized coordinates `(x, y)` on the primary
/// display (`0.0..=1.0`).
pub fn move_absolute(x: f32, y: f32) {
    if !x.is_finite() || !y.is_finite() {
        return;
    }
    let ax = (x.clamp(0.0, 1.0) * 65535.0).round() as i32;
    let ay = (y.clamp(0.0, 1.0) * 65535.0).round() as i32;
    send_inputs(&[mouse_input(ax, ay, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE)]);
}

/// Injects a normalized touch event (`0.0..=1.0`) from reverse PC mirroring as
/// absolute mouse movement + left button press/release.
pub fn mirror_touch(action: TouchAction, x: f32, y: f32) {
    if !x.is_finite() || !y.is_finite() {
        return;
    }
    let ax = (x.clamp(0.0, 1.0) * 65535.0).round() as i32;
    let ay = (y.clamp(0.0, 1.0) * 65535.0).round() as i32;

    if is_screen_locked() {
        let action_str = match action {
            TouchAction::Down => "down",
            TouchAction::Move => "move",
            TouchAction::Up => "up",
        };
        let msg = format!("TOUCH {} {:.5} {:.5}\n", action_str, x.clamp(0.0, 1.0), y.clamp(0.0, 1.0));
        if send_to_lock_pipe(&msg) {
            return;
        }
    }
    match action {
        TouchAction::Down => {
            if let Ok(mut s) = STATE.lock() {
                s.held_left = true;
            }
            send_inputs(&[
                mouse_input(ax, ay, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE),
                mouse_input(ax, ay, 0, MOUSEEVENTF_LEFTDOWN),
            ]);
        }
        TouchAction::Move => {
            send_inputs(&[mouse_input(ax, ay, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE)]);
        }
        TouchAction::Up => {
            if let Ok(mut s) = STATE.lock() {
                s.held_left = false;
            }
            send_inputs(&[
                mouse_input(ax, ay, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE),
                mouse_input(ax, ay, 0, MOUSEEVENTF_LEFTUP),
            ]);
        }
    }
}

/// Presses, releases or clicks a mouse button.
pub fn mouse_button(button: MouseButton, action: ButtonAction) {
    let (down_flag, up_flag) = match button {
        MouseButton::Left => (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP),
        MouseButton::Right => (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP),
        MouseButton::Middle => (MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP),
    };
    {
        let mut s = STATE.lock().unwrap_or_else(|e| e.into_inner());
        let slot = match button {
            MouseButton::Left => &mut s.held_left,
            MouseButton::Right => &mut s.held_right,
            MouseButton::Middle => &mut s.held_middle,
        };
        *slot = matches!(action, ButtonAction::Down);
    }
    match action {
        ButtonAction::Down => send_inputs(&[mouse_input(0, 0, 0, down_flag)]),
        ButtonAction::Up => send_inputs(&[mouse_input(0, 0, 0, up_flag)]),
        ButtonAction::Click => {
            send_inputs(&[mouse_input(0, 0, 0, down_flag), mouse_input(0, 0, 0, up_flag)]);
        }
    }
}

/// Releases any mouse buttons still held down by remote input.
pub fn release_held_buttons() {
    let (left, right, middle) = {
        let mut s = STATE.lock().unwrap_or_else(|e| e.into_inner());
        let held = (s.held_left, s.held_right, s.held_middle);
        s.held_left = false;
        s.held_right = false;
        s.held_middle = false;
        s.move_rem_x = 0.0;
        s.move_rem_y = 0.0;
        s.scroll_rem_x = 0.0;
        s.scroll_rem_y = 0.0;
        held
    };
    let mut inputs = Vec::new();
    if left {
        inputs.push(mouse_input(0, 0, 0, MOUSEEVENTF_LEFTUP));
    }
    if right {
        inputs.push(mouse_input(0, 0, 0, MOUSEEVENTF_RIGHTUP));
    }
    if middle {
        inputs.push(mouse_input(0, 0, 0, MOUSEEVENTF_MIDDLEUP));
    }
    send_inputs(&inputs);
}

/// Scrolls the mouse wheel smoothly (`dy` > 0 scrolls down, `dx` > 0 scrolls right,
/// in wheel notches of `WHEEL_DELTA = 120`).
pub fn scroll(dx: f32, dy: f32) {
    if !dx.is_finite() || !dy.is_finite() {
        return;
    }
    let delta = WHEEL_DELTA as f32;
    let (wx, wy) = {
        let mut s = STATE.lock().unwrap_or_else(|e| e.into_inner());
        // Protocol: positive dy = scroll down = negative Win32 wheel delta.
        // Protocol: positive dx = scroll right = positive Win32 horizontal wheel delta.
        let total_x = s.scroll_rem_x + dx * delta;
        let total_y = s.scroll_rem_y - dy * delta;
        let wx = total_x.round() as i32;
        let wy = total_y.round() as i32;
        s.scroll_rem_x = total_x - (wx as f32);
        s.scroll_rem_y = total_y - (wy as f32);
        (wx, wy)
    };
    let mut inputs = Vec::with_capacity(2);
    if wy != 0 {
        inputs.push(mouse_input(0, 0, wy, MOUSEEVENTF_WHEEL));
    }
    if wx != 0 {
        inputs.push(mouse_input(0, 0, wx, MOUSEEVENTF_HWHEEL));
    }
    send_inputs(&inputs);
}

/// Types arbitrary text via virtual key + scan code for standard characters
/// (so that secure password/PIN prompts on the Windows lock screen and LogonUI accept them),
/// falling back to `KEYEVENTF_UNICODE` for non-ASCII or complex Unicode.
pub fn type_text(text: &str) {
    if is_screen_locked() {
        let clean = text.trim_matches(|c| c == '\r' || c == '\n');
        if !clean.is_empty() {
            let msg = format!("TYPE {}\n", clean);
            if send_to_lock_pipe(&msg) {
                return;
            }
        }
    }

    unsafe extern "system" {
        fn VkKeyScanW(ch: u16) -> i16;
    }

    let mut inputs = Vec::with_capacity(text.len() * 4);
    for ch in text.chars() {
        if ch == '\r' {
            continue;
        }
        if ch == '\n' {
            inputs.push(vk_input(VK_RETURN, false, false));
            inputs.push(vk_input(VK_RETURN, false, true));
            continue;
        }
        if ch == '\t' {
            inputs.push(vk_input(VK_TAB, false, false));
            inputs.push(vk_input(VK_TAB, false, true));
            continue;
        }
        let mut buf = [0u16; 2];
        let units = ch.encode_utf16(&mut buf);
        for &unit in units.iter() {
            let vk_scan = unsafe { VkKeyScanW(unit) };
            let ctrl_or_alt = (vk_scan != -1) && (((vk_scan >> 8) & 6) != 0);
            if vk_scan != -1 && !ctrl_or_alt {
                let vk = (vk_scan & 0xFF) as u16;
                let shift = ((vk_scan >> 8) & 1) != 0;
                if shift {
                    inputs.push(vk_input(VK_SHIFT, false, false));
                }
                inputs.push(vk_input(VIRTUAL_KEY(vk), false, false));
                inputs.push(vk_input(VIRTUAL_KEY(vk), false, true));
                if shift {
                    inputs.push(vk_input(VK_SHIFT, false, true));
                }
            } else {
                inputs.push(unicode_input(unit, false));
                inputs.push(unicode_input(unit, true));
            }
        }
    }
    send_inputs(&inputs);
}

/// Resolves a protocol key or shortcut name into `(VIRTUAL_KEY, is_extended, extra_mods)`.
fn resolve_key(key: &str) -> Option<(VIRTUAL_KEY, bool, &'static [KeyMod])> {
    const CTRL: &[KeyMod] = &[KeyMod::Ctrl];
    const WIN: &[KeyMod] = &[KeyMod::Win];
    const NONE: &[KeyMod] = &[];

    Some(match key {
        "copy" => (VK_C, false, CTRL),
        "paste" => (VK_V, false, CTRL),
        "cut" => (VK_X, false, CTRL),
        "undo" => (VK_Z, false, CTRL),
        "redo" => (VK_Y, false, CTRL),
        "select_all" => (VK_A, false, CTRL),
        "task_view" => (VK_TAB, false, WIN),
        "enter" => (VK_RETURN, false, NONE),
        "backspace" => (VK_BACK, false, NONE),
        "tab" => (VK_TAB, false, NONE),
        "escape" => (VK_ESCAPE, false, NONE),
        "space" => (VK_SPACE, false, NONE),
        "delete" => (VK_DELETE, true, NONE),
        "insert" => (VK_INSERT, true, NONE),
        "up" => (VK_UP, true, NONE),
        "down" => (VK_DOWN, true, NONE),
        "left" => (VK_LEFT, true, NONE),
        "right" => (VK_RIGHT, true, NONE),
        "home" => (VK_HOME, true, NONE),
        "end" => (VK_END, true, NONE),
        "page_up" => (VK_PRIOR, true, NONE),
        "page_down" => (VK_NEXT, true, NONE),
        "f1" => (VK_F1, false, NONE),
        "f2" => (VIRTUAL_KEY(VK_F1.0 + 1), false, NONE),
        "f3" => (VIRTUAL_KEY(VK_F1.0 + 2), false, NONE),
        "f4" => (VIRTUAL_KEY(VK_F1.0 + 3), false, NONE),
        "f5" => (VK_F5, false, NONE),
        "f6" => (VIRTUAL_KEY(VK_F1.0 + 5), false, NONE),
        "f7" => (VIRTUAL_KEY(VK_F1.0 + 6), false, NONE),
        "f8" => (VIRTUAL_KEY(VK_F1.0 + 7), false, NONE),
        "f9" => (VIRTUAL_KEY(VK_F1.0 + 8), false, NONE),
        "f10" => (VIRTUAL_KEY(VK_F1.0 + 9), false, NONE),
        "f11" => (VIRTUAL_KEY(VK_F1.0 + 10), false, NONE),
        "f12" => (VIRTUAL_KEY(VK_F1.0 + 11), false, NONE),
        "volume_up" => (VK_VOLUME_UP, true, NONE),
        "volume_down" => (VK_VOLUME_DOWN, true, NONE),
        "volume_mute" => (VK_VOLUME_MUTE, true, NONE),
        "play_pause" => (VK_MEDIA_PLAY_PAUSE, true, NONE),
        "next_track" => (VK_MEDIA_NEXT_TRACK, true, NONE),
        "prev_track" => (VK_MEDIA_PREV_TRACK, true, NONE),
        "zoom_in" | "magnify_in" => (VIRTUAL_KEY(0xBB), false, WIN),
        "zoom_out" | "magnify_out" => (VIRTUAL_KEY(0xBD), false, WIN),
        "magnify_exit" => (VK_ESCAPE, false, WIN),
        s if s.len() == 1 => {
            let b = s.as_bytes()[0];
            if b.is_ascii_lowercase() {
                (VIRTUAL_KEY(u16::from(b - b'a' + b'A')), false, NONE)
            } else if b.is_ascii_digit() {
                (VIRTUAL_KEY(u16::from(b)), false, NONE)
            } else {
                return None;
            }
        }
        _ => return None,
    })
}

fn mod_vk(m: KeyMod) -> (VIRTUAL_KEY, bool) {
    match m {
        KeyMod::Ctrl => (VK_CONTROL, false),
        KeyMod::Alt => (VK_MENU, false),
        KeyMod::Shift => (VK_SHIFT, false),
        KeyMod::Win => (VK_LWIN, true),
    }
}

/// Presses a named key or shortcut with optional modifiers.
pub fn press_key(key: &str, mods: &[KeyMod]) {
    if key == "lock" {
        let _ = crate::links::power(PowerAction::Lock);
        return;
    }
    if is_screen_locked() {
        if key == "enter" {
            let _ = send_to_lock_pipe("KEY 13 28 0\n");
            let _ = send_to_lock_pipe("KEY 13 28 2\n");
            return;
        }
        if key == "backspace" {
            let _ = send_to_lock_pipe("KEY 8 14 0\n");
            let _ = send_to_lock_pipe("KEY 8 14 2\n");
            return;
        }
        if key == "space" {
            let _ = send_to_lock_pipe("KEY 32 57 0\n");
            let _ = send_to_lock_pipe("KEY 32 57 2\n");
            return;
        }
        if key == "escape" {
            let _ = send_to_lock_pipe("KEY 27 1 0\n");
            let _ = send_to_lock_pipe("KEY 27 1 2\n");
            return;
        }
    }
    let Some((vk, extended, preset_mods)) = resolve_key(key) else {
        return;
    };
    let mut all_mods: Vec<KeyMod> = Vec::with_capacity(4);
    for &m in preset_mods.iter().chain(mods.iter()) {
        if !all_mods.contains(&m) {
            all_mods.push(m);
        }
    }
    let mut inputs = Vec::with_capacity(all_mods.len() * 2 + 2);
    for &m in &all_mods {
        let (mvk, mext) = mod_vk(m);
        inputs.push(vk_input(mvk, mext, false));
    }
    inputs.push(vk_input(vk, extended, false));
    inputs.push(vk_input(vk, extended, true));
    for &m in all_mods.iter().rev() {
        let (mvk, mext) = mod_vk(m);
        inputs.push(vk_input(mvk, mext, true));
    }
    send_inputs(&inputs);
}

/// Executes a presentation slide action.
pub fn slide(action: SlideAction) {
    let (vk, extended) = match action {
        SlideAction::Next => (VK_NEXT, true),
        SlideAction::Previous => (VK_PRIOR, true),
        SlideAction::Start => (VK_F5, false),
        SlideAction::Stop => (VK_ESCAPE, false),
        SlideAction::Black => (VK_B, false),
    };
    send_inputs(&[vk_input(vk, extended, false), vk_input(vk, extended, true)]);
}

#[repr(C)]
struct LastInputInfo {
    cb_size: u32,
    dw_time: u32,
}

unsafe extern "system" {
    fn GetLastInputInfo(plii: *mut LastInputInfo) -> i32;
    fn GetTickCount() -> u32;
}

/// Returns `true` when the user has moved the mouse or pressed a key on this PC
/// within the last `max_idle_ms` milliseconds.
pub fn is_user_active_within_ms(max_idle_ms: u32) -> bool {
    let mut info = LastInputInfo { cb_size: std::mem::size_of::<LastInputInfo>() as u32, dw_time: 0 };
    let ok = unsafe { GetLastInputInfo(&mut info) };
    if ok == 0 {
        return false;
    }
    let now = unsafe { GetTickCount() };
    now.wrapping_sub(info.dw_time) <= max_idle_ms
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resolves_named_keys_and_shortcuts() {
        assert_eq!(resolve_key("enter"), Some((VK_RETURN, false, &[][..])));
        assert_eq!(resolve_key("up"), Some((VK_UP, true, &[][..])));
        assert_eq!(resolve_key("copy"), Some((VK_C, false, &[KeyMod::Ctrl][..])));
        assert_eq!(resolve_key("paste"), Some((VK_V, false, &[KeyMod::Ctrl][..])));
        assert_eq!(resolve_key("task_view"), Some((VK_TAB, false, &[KeyMod::Win][..])));
        assert_eq!(resolve_key("a"), Some((VK_A, false, &[][..])));
        assert_eq!(resolve_key("f5"), Some((VK_F5, false, &[][..])));
        assert!(resolve_key("unknown_key").is_none());
    }
}

