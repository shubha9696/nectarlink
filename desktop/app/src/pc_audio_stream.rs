// SPDX-License-Identifier: GPL-3.0-or-later
//! Phone as Speaker / Headphones for PC Audio (§3.8 P2): captures Windows
//! system audio via WASAPI loopback (`AUDCLNT_STREAMFLAGS_LOOPBACK`), converts
//! 32-bit float stereo samples to 16-bit signed little-endian PCM, and streams
//! them to connected phones over NectarLink's low-latency audio stream.

/// Where the PC's sound goes: only to the phone that asked for it, waiting
/// while it's away (never to another phone, which may be someone else's), or
/// to the first online phone when the PC itself turned the speaker on.
#[cfg_attr(not(any(windows, target_os = "linux")), allow(dead_code))]
fn pick_target(
    wanted: Option<nectarlink_core::DeviceId>,
    online: &[nectarlink_core::DeviceId],
) -> Option<nectarlink_core::DeviceId> {
    match wanted {
        Some(phone) => online.contains(&phone).then_some(phone),
        None => online.first().copied(),
    }
}

#[cfg(test)]
mod tests {
    use super::pick_target;
    use nectarlink_core::DeviceId;

    #[test]
    fn sound_never_moves_to_a_phone_that_did_not_ask() {
        let (a, b) = (DeviceId([1; 32]), DeviceId([2; 32]));
        assert_eq!(pick_target(Some(a), &[b, a]), Some(a));
        assert_eq!(pick_target(Some(a), &[b]), None);
        assert_eq!(pick_target(None, &[b, a]), Some(b));
        assert_eq!(pick_target(None, &[]), None);
    }
}

#[cfg(any(windows, target_os = "linux"))]
mod imp {

    #![allow(unsafe_code, unused_imports)]

    use std::{
        sync::atomic::{AtomicBool, Ordering},
        time::Duration,
    };

    #[cfg(windows)]
    use windows::Win32::{
        Foundation::CloseHandle,
        Media::Audio::{
            AUDCLNT_BUFFERFLAGS_SILENT, AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_EVENTCALLBACK,
            AUDCLNT_STREAMFLAGS_LOOPBACK, IAudioCaptureClient, IAudioClient, IMMDeviceEnumerator,
            MMDeviceEnumerator, WAVEFORMATEX, eConsole, eRender,
        },
        System::{
            Com::{CLSCTX_ALL, COINIT_MULTITHREADED, CoCreateInstance, CoInitializeEx, CoTaskMemFree},
            Threading::{CreateEventW, GetCurrentThread, SetThreadPriority, THREAD_PRIORITY_TIME_CRITICAL, WaitForSingleObject},
        },
    };

    use crate::core_host;
    use tokio::io::AsyncWriteExt;

    static ENABLED: AtomicBool = AtomicBool::new(false);
    static WORKER_RUNNING: AtomicBool = AtomicBool::new(false);
    static EXACT_QUALITY: AtomicBool = AtomicBool::new(true);
    /// The phone that last turned "Phone speaker" on. Audio goes only there; when
    /// the PC turned it on, to the first phone that is online.
    static TARGET: std::sync::Mutex<Option<nectarlink_core::DeviceId>> = std::sync::Mutex::new(None);

    /// Returns whether Exact Quality (lossless stereo & Dolby surround) streaming is enabled.
    pub fn is_exact_quality() -> bool {
        EXACT_QUALITY.load(Ordering::SeqCst)
    }

    /// Sets whether Exact Quality mode is active.
    pub fn set_exact_quality(exact: bool) {
        EXACT_QUALITY.store(exact, Ordering::SeqCst);
    }

    /// The phone the sound goes to first, if one asked.
    pub fn target() -> Option<nectarlink_core::DeviceId> {
        *TARGET.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Records which phone asked to be the speaker.
    pub fn set_target(phone: Option<nectarlink_core::DeviceId>) {
        *TARGET.lock().unwrap_or_else(|e| e.into_inner()) = phone;
    }

    /// Returns whether PC-to-phone audio streaming is currently enabled.
    pub fn is_enabled() -> bool {
        ENABLED.load(Ordering::SeqCst)
    }

    /// Enables or disables PC-to-phone audio loopback streaming.
    pub fn set_enabled(enabled: bool) {
        if enabled {
            crate::phone_sound::stop_active();
        }
        ENABLED.store(enabled, Ordering::SeqCst);
        if enabled {
            ensure_worker();
        }
    }

    /// Converts interleaved stereo `f32` samples (`[-1.0, 1.0]`) into 16-bit
    /// signed little-endian PCM bytes (`pcm_s16le`).
    #[cfg(test)]
    pub fn f32_stereo_to_i16le(samples: &[f32]) -> Vec<u8> {
        let mut out = Vec::with_capacity(samples.len() * 2);
        for &sample in samples {
            let clean = if sample.is_finite() { sample.clamp(-1.0, 1.0) } else { 0.0 };
            let scaled = (clean * 32767.0).round() as i16;
            out.extend_from_slice(&scaled.to_le_bytes());
        }
        out
    }

    struct AudioEncoder;

    impl AudioEncoder {
        fn new(_sample_rate: u32) -> Self {
            Self
        }

        fn encode_stereo_f32(&mut self, floats: &[f32], channels: usize, _exact: bool) -> Vec<u8> {
            let frames = floats.len() / channels.max(1);
            let mut out = Vec::with_capacity(frames * 4);
            for frame in floats.chunks_exact(channels.max(1)) {
                let (l_in, r_in) = if channels == 2 {
                    (
                        if frame[0].is_finite() { frame[0] } else { 0.0 },
                        if frame[1].is_finite() { frame[1] } else { 0.0 },
                    )
                } else if channels >= 6 {
                    let fl = if frame[0].is_finite() { frame[0] } else { 0.0 };
                    let fr = if frame[1].is_finite() { frame[1] } else { 0.0 };
                    let c = if frame[2].is_finite() { frame[2] } else { 0.0 };
                    let lfe = if frame[3].is_finite() { frame[3] } else { 0.0 };
                    let ls = if frame[4].is_finite() { frame[4] } else { 0.0 };
                    let rs = if frame[5].is_finite() { frame[5] } else { 0.0 };
                    let (bl, br) = if channels >= 8 {
                        (
                            if frame[6].is_finite() { frame[6] } else { 0.0 },
                            if frame[7].is_finite() { frame[7] } else { 0.0 },
                        )
                    } else {
                        (0.0, 0.0)
                    };
                    let l = fl * 0.28 + c * 0.20 + lfe * 0.20 + ls * 0.20 + bl * 0.12;
                    let r = fr * 0.28 + c * 0.20 + lfe * 0.20 + rs * 0.20 + br * 0.12;
                    (l, r)
                } else if channels > 1 {
                    (
                        if frame[0].is_finite() { frame[0] } else { 0.0 },
                        if frame[1].is_finite() { frame[1] } else { 0.0 },
                    )
                } else {
                    let mono = if frame[0].is_finite() { frame[0] } else { 0.0 };
                    (mono, mono)
                };

                let l_clean = l_in.clamp(-1.0, 1.0);
                let r_clean = r_in.clamp(-1.0, 1.0);
                let l_i16 = (l_clean * 32767.0).round().clamp(-32768.0, 32767.0) as i16;
                let r_i16 = (r_clean * 32767.0).round().clamp(-32768.0, 32767.0) as i16;
                out.extend_from_slice(&l_i16.to_le_bytes());
                out.extend_from_slice(&r_i16.to_le_bytes());
            }
            out
        }

        #[cfg_attr(target_os = "linux", allow(dead_code))]
        fn encode_stereo_i32(&mut self, samples: &[i32], channels: usize, exact: bool) -> Vec<u8> {
            let mut floats = Vec::with_capacity(samples.len());
            for &s in samples {
                floats.push((s as f32) / 2147483648.0);
            }
            self.encode_stereo_f32(&floats, channels, exact)
        }

        #[cfg_attr(target_os = "linux", allow(dead_code))]
        fn encode_stereo_i24(&mut self, bytes: &[u8], channels: usize, exact: bool) -> Vec<u8> {
            let bytes_per_frame = channels.max(1) * 3;
            let frames = bytes.len() / bytes_per_frame;
            let mut floats = Vec::with_capacity(frames * channels);
            for frame in bytes.chunks_exact(bytes_per_frame) {
                for ch in 0..channels {
                    let offset = ch * 3;
                    let i24 = i32::from_le_bytes([0, frame[offset], frame[offset + 1], frame[offset + 2]]) >> 8;
                    floats.push((i24 as f32) / 8388608.0);
                }
            }
            self.encode_stereo_f32(&floats, channels, exact)
        }

        #[cfg_attr(target_os = "linux", allow(dead_code))]
        fn encode_stereo_i16(&mut self, samples: &[i16], channels: usize, exact: bool) -> Vec<u8> {
            let mut floats = Vec::with_capacity(samples.len());
            for &s in samples {
                floats.push((s as f32) / 32768.0);
            }
            self.encode_stereo_f32(&floats, channels, exact)
        }
    }

    fn ensure_worker() {
        if WORKER_RUNNING.compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst).is_err() {
            return;
        }
        std::thread::Builder::new()
            .name("nectarlink-pc-audio-loopback".into())
            .spawn(|| {
                run_loopback_worker();
                WORKER_RUNNING.store(false, Ordering::SeqCst);
            })
            .ok();
    }

    /// Formats a PulseAudio/PipeWire sink name into its monitor source name.
    #[cfg_attr(windows, allow(dead_code))]
    pub(crate) fn format_monitor_source_name(default_sink: Option<&str>) -> String {
        let Some(trimmed) = default_sink.map(str::trim).filter(|s| !s.is_empty()) else {
            return "@DEFAULT_MONITOR@".to_owned();
        };
        if trimmed.ends_with(".monitor") { trimmed.to_owned() } else { format!("{trimmed}.monitor") }
    }

    /// Builds `pw-record` CLI arguments that capture desktop sink monitor loopback
    /// (`-P stream.capture.sink=true`) rather than the default microphone input.
    #[cfg_attr(windows, allow(dead_code))]
    pub(crate) fn pw_record_loopback_args(monitor: &str) -> Vec<String> {
        let mut args = vec![
            "--raw".to_owned(),
            "--format=f32".to_owned(),
            "--rate=48000".to_owned(),
            "--channels=2".to_owned(),
            "-P".to_owned(),
            "stream.capture.sink=true".to_owned(),
        ];
        let trimmed = monitor.trim();
        if !trimmed.is_empty() && trimmed != "@DEFAULT_MONITOR@" {
            let sink_name = trimmed.strip_suffix(".monitor").unwrap_or(trimmed).trim();
            if !sink_name.is_empty() && !sink_name.starts_with('-') {
                args.push("--target".to_owned());
                args.push(sink_name.to_owned());
            }
        }
        args.push("-".to_owned());
        args
    }

    #[cfg(target_os = "linux")]
    fn resolve_default_monitor_source() -> String {
        let default_sink = std::process::Command::new("pactl")
            .arg("get-default-sink")
            .output()
            .ok()
            .filter(|o| o.status.success())
            .map(|o| String::from_utf8_lossy(&o.stdout).into_owned());
        format_monitor_source_name(default_sink.as_deref())
    }

    #[cfg(windows)]
    fn run_loopback_worker() {
        while is_enabled() {
            if let Err(e) = capture_and_stream_once() {
                tracing::debug!(error = %e, "PC audio loopback stream cycle ended");
                std::thread::sleep(Duration::from_millis(500));
            }
        }
    }

    #[cfg(windows)]
    fn capture_and_stream_once() -> Result<(), String> {
        use std::io::Read;
        use std::process::{Command, Stdio};

        let node = core_host::node().ok_or_else(|| "core node not running".to_owned())?;
        let peers: Vec<_> = node
            .paired_devices()
            .unwrap_or_default()
            .into_iter()
            .filter(|d| matches!(d.link, nectarlink_core::LinkState::Online { .. }))
            .map(|d| d.id)
            .collect();
        let wanted = *TARGET.lock().unwrap_or_else(|e| e.into_inner());
        let Some(peer) = super::pick_target(wanted, &peers) else {
            std::thread::sleep(Duration::from_millis(500));
            return Ok(());
        };
        if crate::phone_sound::active().is_some() || crate::mirror::has_sound(&peer) {
            // Never loop back PC audio when phone audio is playing through PC speakers (prevents reverberation)
            std::thread::sleep(Duration::from_millis(200));
            return Ok(());
        }
        let stop = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
        let studio_quality = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(is_exact_quality()));
        let (tx, rx) = tokio::sync::mpsc::channel::<bytes::Bytes>(24);

        let stop_clone = stop.clone();
        let studio_clone = studio_quality.clone();
        let wasapi_thread = std::thread::spawn(move || {
            let _ = crate::win::audio::run_wasapi_loopback(tx, stop_clone, studio_clone);
        });

        let send_node = node.clone();
        core_host::spawn(async move {
            let stream_res = send_node.open_pc_audio_stream(peer).await;
            if let Ok(stream) = stream_res {
                let _ = nectarlink_core::speaker::send_pc_audio_stream(stream, rx).await;
            }
        });

        while is_enabled() {
            if target() != wanted && target().is_some_and(|t| peers.contains(&t)) {
                break;
            }
            studio_quality.store(is_exact_quality(), std::sync::atomic::Ordering::Relaxed);
            std::thread::sleep(Duration::from_millis(100));
        }

        stop.store(true, std::sync::atomic::Ordering::Relaxed);
        let _ = wasapi_thread.join();
        Ok(())
    }

    #[cfg(not(windows))]
    fn run_loopback_worker() {}

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn f32_stereo_to_i16le_clamps_and_encodes_little_endian() {
            let samples = [0.0_f32, 1.0, -1.0, 2.5, -3.0, f32::NAN];
            let bytes = f32_stereo_to_i16le(&samples);
            assert_eq!(bytes.len(), samples.len() * 2);
            let decoded: Vec<i16> = bytes.as_chunks::<2>().0.iter().map(|&c| i16::from_le_bytes(c)).collect();
            assert_eq!(decoded, vec![0, 32767, -32767, 32767, -32767, 0]);
        }

        #[test]
        fn set_enabled_toggles_atomic_state_cleanly() {
            set_enabled(false);
            assert!(!is_enabled());
            set_enabled(true);
            assert!(is_enabled());
            set_enabled(false);
            assert!(!is_enabled());
        }

        #[test]
        fn format_monitor_source_name_appends_monitor_suffix_or_uses_default() {
            assert_eq!(
                format_monitor_source_name(Some("alsa_output.pci-0000_00_1f.3.analog-stereo\n")),
                "alsa_output.pci-0000_00_1f.3.analog-stereo.monitor"
            );
            assert_eq!(
                format_monitor_source_name(Some("alsa_output.pci.monitor")),
                "alsa_output.pci.monitor"
            );
            assert_eq!(format_monitor_source_name(Some("  ")), "@DEFAULT_MONITOR@");
            assert_eq!(format_monitor_source_name(None), "@DEFAULT_MONITOR@");

            let default_pw = pw_record_loopback_args("@DEFAULT_MONITOR@");
            assert!(default_pw.windows(2).any(|w| w == ["-P", "stream.capture.sink=true"]));
            assert!(!default_pw.iter().any(|a| a == "--target"));
            assert_eq!(default_pw.last().map(String::as_str), Some("-"));

            let targeted_pw = pw_record_loopback_args("alsa_output.pci-0000_00_1f.3.analog-stereo.monitor");
            assert!(targeted_pw.windows(2).any(|w| w == ["-P", "stream.capture.sink=true"]));
            assert!(
                targeted_pw
                    .windows(2)
                    .any(|w| w == ["--target", "alsa_output.pci-0000_00_1f.3.analog-stereo"])
            );
            assert_eq!(targeted_pw.last().map(String::as_str), Some("-"));
        }

        #[test]
        fn audio_encoder_encodes_stereo_f32_and_i16_without_clipping() {
            let mut enhancer = AudioEncoder::new(48_000);
            let floats = [0.25_f32, -0.25, 0.95, -0.95, f32::NAN, 0.5];
            let encoded = enhancer.encode_stereo_f32(&floats, 2, false);
            assert_eq!(encoded.len(), 3 * 4);
            assert!(encoded.iter().any(|&b| b != 0));

            let i16s = [8000i16, -8000, 16000, -16000];
            let encoded_i16 = enhancer.encode_stereo_i16(&i16s, 2, false);
            assert_eq!(encoded_i16.len(), 2 * 4);
            assert!(encoded_i16.iter().any(|&b| b != 0));
        }
    }
}

#[cfg(not(any(windows, target_os = "linux")))]
mod imp {
    #![allow(dead_code)]

    pub fn target() -> Option<nectarlink_core::DeviceId> {
        None
    }

    pub fn set_target(_phone: Option<nectarlink_core::DeviceId>) {}

    pub fn is_enabled() -> bool {
        false
    }

    pub fn set_enabled(_enabled: bool) {}

    pub fn is_exact_quality() -> bool { true }
    pub fn set_exact_quality(_exact: bool) {}

    pub fn f32_stereo_to_i16le(_samples: &[f32]) -> Vec<u8> {
        Vec::new()
    }
}

pub use imp::*;
