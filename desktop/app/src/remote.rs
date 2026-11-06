// SPDX-License-Identifier: GPL-3.0-or-later
//! Dispatches remote touchpad, keyboard and presentation input from a paired
//! phone to `win::input` and updates the laser pointer overlay state.

use std::{
    sync::atomic::{AtomicU64, Ordering},
    time::Duration,
};

use nectarlink_core::{DeviceId, LinkState, NodeEvent, RemoteInput};

use crate::{core_host, state::Changes, win};

/// When the laser pointer last heard from its phone (ms since the Unix
/// epoch; 0 when it's off). The phone repeats it every second while held,
/// so a dot that stops hearing (the phone left) is hidden.
static LASER_SEEN: AtomicU64 = AtomicU64::new(0);
const LASER_TIMEOUT: Duration = Duration::from_secs(4);

fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as u64)
}

fn hide_laser() {
    LASER_SEEN.store(0, Ordering::SeqCst);
    core_host::host().hub.update(|s| if s.laser.take().is_some() { Changes::REMOTE } else { Changes::NONE });
}

pub fn handle_input(peer: &DeviceId, input: RemoteInput) {
    let _ = crate::input_share::enter_remote(*peer);
    match input {
        RemoteInput::Move { dx, dy } => win::input::move_relative(dx, dy),
        RemoteInput::Button { button, action } => win::input::mouse_button(button, action),
        RemoteInput::Scroll { dx, dy } => win::input::scroll(dx, dy),
        RemoteInput::Text { text } => {
            if let Some(wav_b64) = text.strip_prefix("__NECTAR_DICTATE_WAV__:") {
                handle_dictate_audio(wav_b64);
            } else {
                win::input::type_text(&text);
            }
        }
        RemoteInput::Key { key, mods } => win::input::press_key(&key, &mods),
        RemoteInput::Slide { action } => win::input::slide(action),
        RemoteInput::Laser { on: true, x, y } => {
            let x = x.clamp(0.0, 1.0);
            let y = y.clamp(0.0, 1.0);
            let peer = *peer;
            core_host::host().hub.update(|s| {
                let next = Some((peer, x, y));
                if s.laser == next {
                    return Changes::NONE;
                }
                s.laser = next;
                Changes::REMOTE
            });
            // One watchdog while it's shown.
            if LASER_SEEN.swap(now_ms(), Ordering::SeqCst) == 0 {
                core_host::spawn(async {
                    loop {
                        tokio::time::sleep(Duration::from_millis(500)).await;
                        let seen = LASER_SEEN.load(Ordering::SeqCst);
                        if seen == 0 {
                            return;
                        }
                        if now_ms().saturating_sub(seen) > LASER_TIMEOUT.as_millis() as u64 {
                            hide_laser();
                            return;
                        }
                    }
                });
            }
        }
        RemoteInput::Laser { on: false, .. } => hide_laser(),
    }
}

/// Clears any active laser pointer and releases held mouse buttons (e.g. when
/// `remote_input` is turned off).
pub fn stop_all() {
    let _ = crate::input_share::leave_remote();
    win::input::release_held_buttons();
    hide_laser();
}

pub fn on_event(event: &NodeEvent) {
    match event {
        NodeEvent::LinkChanged { link: LinkState::Offline { .. }, .. } | NodeEvent::DeviceRemoved(_) => {
            let _ = crate::input_share::leave_remote();
            win::input::release_held_buttons();
        }
        NodeEvent::RemoteInputRequested { .. } => {
            core_host::host().hub.update(|s| s.apply(event));
        }
        _ => {}
    }
}

fn handle_dictate_audio(wav_b64: &str) {
    let wav_bytes = match data_encoding::BASE64.decode(wav_b64.as_bytes()) {
        Ok(b) => b,
        Err(e) => {
            tracing::warn!(error = %e, "failed to decode dictate wav base64");
            return;
        }
    };
    if wav_bytes.is_empty() {
        return;
    }

    let temp_dir = std::env::temp_dir();
    let wav_file = temp_dir.join(format!("nectar_dictate_{}.wav", now_ms()));
    if let Err(e) = std::fs::write(&wav_file, &wav_bytes) {
        tracing::warn!(error = %e, "failed to write dictate wav file");
        return;
    }

    let mut helper_paths = Vec::new();
    if let Ok(exe) = std::env::current_exe() {
        if let Some(parent) = exe.parent() {
            helper_paths.push(parent.join("speech_helper.exe"));
        }
    }
    helper_paths.push(std::path::PathBuf::from(r"C:\nectarlink-app\speech_helper.exe"));
    helper_paths.push(std::path::PathBuf::from(r"C:\Users\shubh\speech_helper.exe"));

    let helper = helper_paths.into_iter().find(|p| p.exists());
    let recognized_text = if let Some(helper_path) = helper {
        #[cfg(target_os = "windows")]
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        let mut cmd = std::process::Command::new(helper_path);
        cmd.arg(&wav_file);
        #[cfg(target_os = "windows")]
        cmd.creation_flags(CREATE_NO_WINDOW);
        match cmd.output() {
            Ok(out) => {
                let stdout = String::from_utf8_lossy(&out.stdout);
                let trimmed = stdout.trim();
                if let Some(rest) = trimmed.strip_prefix("RECOGNIZED:") {
                    Some(rest.trim().to_string())
                } else {
                    tracing::info!(output = %trimmed, "speech_helper recognition result");
                    None
                }
            }
            Err(e) => {
                tracing::warn!(error = %e, "failed to run speech_helper.exe");
                None
            }
        }
    } else {
        tracing::warn!("speech_helper.exe not found");
        None
    };

    let _ = std::fs::remove_file(wav_file);

    if let Some(text) = recognized_text {
        if !text.is_empty() {
            tracing::info!(%text, "Transcribed phone speech recognition, typing into Windows");
            win::input::type_text(&text);
            let _ = crate::win::clipboard::write(&text);
        }
    }
}
