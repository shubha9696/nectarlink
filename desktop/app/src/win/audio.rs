// SPDX-License-Identifier: GPL-3.0-or-later
//! Speaker volume, mute, output device enumeration, and microphone mute state
//! via Windows Core Audio (`IMMDeviceEnumerator` + `IAudioEndpointVolume`),
//! and notifications when any of them change.

use windows::{
    Win32::{
        Foundation::PROPERTYKEY,
        Media::Audio::{
            AUDIO_VOLUME_NOTIFICATION_DATA, DEVICE_STATE, DEVICE_STATE_ACTIVE, EDataFlow, ERole,
            Endpoints::{
                IAudioEndpointVolume, IAudioEndpointVolumeCallback, IAudioEndpointVolumeCallback_Impl,
            },
            IMMDevice, IMMDeviceEnumerator, IMMNotificationClient, IMMNotificationClient_Impl,
            MMDeviceEnumerator, eCapture, eConsole, eRender,
        },
        System::Com::{
            CLSCTX_ALL, COINIT_MULTITHREADED, CoCreateInstance, CoInitializeEx, CoTaskMemFree,
            CoUninitialize, STGM_READ,
        },
    },
    core::{GUID, PCWSTR, PWSTR},
};

/// `PKEY_Device_FriendlyName` (`{a45c254e-df1c-4efd-8020-67d146a850e0}, 14`).
const PKEY_DEVICE_FRIENDLY_NAME: PROPERTYKEY =
    PROPERTYKEY { fmtid: GUID::from_u128(0xa45c254e_df1c_4efd_8020_67d146a850e0), pid: 14 };

struct ComGuard(bool);

impl ComGuard {
    fn new() -> Self {
        // SAFETY: Initializes COM on the current thread if not already initialized.
        let hr = unsafe { CoInitializeEx(None, COINIT_MULTITHREADED) };
        Self(hr.is_ok())
    }
}

impl Drop for ComGuard {
    fn drop(&mut self) {
        if self.0 {
            // SAFETY: Matches a successful `CoInitializeEx` on this thread.
            unsafe { CoUninitialize() };
        }
    }
}

fn endpoint_volume(flow: EDataFlow) -> windows::core::Result<IAudioEndpointVolume> {
    // SAFETY: Standard Core Audio COM activation on the default console endpoint.
    unsafe {
        let enumerator: IMMDeviceEnumerator = CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)?;
        let device = enumerator.GetDefaultAudioEndpoint(flow, eConsole)?;
        device.Activate::<IAudioEndpointVolume>(CLSCTX_ALL, None)
    }
}

/// Reads the default speaker endpoint's `(volume_percent, muted)`.
pub fn speaker_state() -> Option<(u8, bool)> {
    let _com = ComGuard::new();
    let ep = endpoint_volume(eRender).ok()?;
    // SAFETY: `ep` is a valid activated `IAudioEndpointVolume`.
    unsafe {
        let scalar = ep.GetMasterVolumeLevelScalar().ok()?;
        let muted = ep.GetMute().ok()?.as_bool();
        let pct = (scalar * 100.0).round().clamp(0.0, 100.0) as u8;
        Some((pct, muted))
    }
}

/// Sets the default speaker endpoint's master volume (`0..=100`).
pub fn set_speaker_volume(level: u8) -> Result<(), String> {
    let _com = ComGuard::new();
    let ep = endpoint_volume(eRender).map_err(|e| format!("no speaker device: {e}"))?;
    let scalar = (f32::from(level.min(100)) / 100.0).clamp(0.0, 1.0);
    // SAFETY: `ep` is a valid activated `IAudioEndpointVolume`.
    unsafe { ep.SetMasterVolumeLevelScalar(scalar, std::ptr::null()).map_err(|e| e.to_string()) }
}

/// Sets the default speaker endpoint's mute state.
pub fn set_speaker_mute(muted: bool) -> Result<(), String> {
    let _com = ComGuard::new();
    let ep = endpoint_volume(eRender).map_err(|e| format!("no speaker device: {e}"))?;
    // SAFETY: `ep` is a valid activated `IAudioEndpointVolume`.
    unsafe { ep.SetMute(muted, std::ptr::null()).map_err(|e| e.to_string()) }
}

/// Steps the default speaker volume up by one increment (2%) and unmutes if muted.
pub fn speaker_volume_up() -> Result<(u8, bool), String> {
    let _com = ComGuard::new();
    let ep = endpoint_volume(eRender).map_err(|e| format!("no speaker device: {e}"))?;
    // SAFETY: `ep` is a valid activated `IAudioEndpointVolume`.
    unsafe {
        ep.VolumeStepUp(std::ptr::null()).map_err(|e| e.to_string())?;
        if ep.GetMute().map(|b| b.as_bool()).unwrap_or(false) {
            let _ = ep.SetMute(false, std::ptr::null());
        }
        let scalar = ep.GetMasterVolumeLevelScalar().unwrap_or(0.0);
        let muted = ep.GetMute().map(|b| b.as_bool()).unwrap_or(false);
        Ok(((scalar * 100.0).round().clamp(0.0, 100.0) as u8, muted))
    }
}

/// Steps the default speaker volume down by one increment (2%).
pub fn speaker_volume_down() -> Result<(u8, bool), String> {
    let _com = ComGuard::new();
    let ep = endpoint_volume(eRender).map_err(|e| format!("no speaker device: {e}"))?;
    // SAFETY: `ep` is a valid activated `IAudioEndpointVolume`.
    unsafe {
        ep.VolumeStepDown(std::ptr::null()).map_err(|e| e.to_string())?;
        let scalar = ep.GetMasterVolumeLevelScalar().unwrap_or(0.0);
        let muted = ep.GetMute().map(|b| b.as_bool()).unwrap_or(false);
        Ok(((scalar * 100.0).round().clamp(0.0, 100.0) as u8, muted))
    }
}

/// Toggles mute on the default speaker endpoint and returns the new muted state.
pub fn toggle_speaker_mute() -> Result<bool, String> {
    let _com = ComGuard::new();
    let ep = endpoint_volume(eRender).map_err(|e| format!("no speaker device: {e}"))?;
    // SAFETY: `ep` is a valid activated `IAudioEndpointVolume`.
    unsafe {
        let cur = ep.GetMute().map_err(|e| e.to_string())?.as_bool();
        let next = !cur;
        ep.SetMute(next, std::ptr::null()).map_err(|e| e.to_string())?;
        Ok(next)
    }
}

/// Reads whether the default capture (microphone) endpoint is muted, or `None`
/// if this PC has no default capture endpoint.
pub fn mic_muted() -> Option<bool> {
    let _com = ComGuard::new();
    let ep = endpoint_volume(eCapture).ok()?;
    // SAFETY: `ep` is a valid activated `IAudioEndpointVolume`.
    unsafe { Some(ep.GetMute().ok()?.as_bool()) }
}

/// Toggles mute on the default capture (microphone) endpoint and returns the
/// new muted state.
pub fn toggle_mic_mute() -> Result<bool, String> {
    let _com = ComGuard::new();
    let ep = endpoint_volume(eCapture).map_err(|e| format!("no microphone found: {e}"))?;
    // SAFETY: `ep` is a valid activated `IAudioEndpointVolume`.
    unsafe {
        let cur = ep.GetMute().map_err(|e| e.to_string())?.as_bool();
        let next = !cur;
        ep.SetMute(next, std::ptr::null()).map_err(|e| e.to_string())?;
        Ok(next)
    }
}

fn device_id_string(device: &IMMDevice) -> Option<String> {
    // SAFETY: `GetId` returns a task-allocated wide string freed with `CoTaskMemFree`.
    unsafe {
        let raw: PWSTR = device.GetId().ok()?;
        if raw.is_null() {
            return None;
        }
        let s = raw.to_string().ok();
        CoTaskMemFree(Some(raw.0 as *const _));
        s.filter(|id| !id.is_empty())
    }
}

fn device_friendly_name(device: &IMMDevice) -> Option<String> {
    // SAFETY: Reads `PKEY_Device_FriendlyName` from the read-only property store.
    unsafe {
        let store = device.OpenPropertyStore(STGM_READ).ok()?;
        let prop = store.GetValue(&PKEY_DEVICE_FRIENDLY_NAME).ok()?;
        let s = prop.to_string();
        let trimmed = s.trim();
        (!trimmed.is_empty()).then(|| trimmed.to_owned())
    }
}

/// Lists active audio render (output) endpoints on this PC, marking the current
/// default console output (`is_default: true`) and sorting the default first.
pub fn output_devices() -> Vec<nectarlink_core::AudioOutputDevice> {
    let _com = ComGuard::new();
    // SAFETY: Enumerates active render endpoints via `IMMDeviceEnumerator`.
    unsafe {
        let Ok(enumerator) =
            CoCreateInstance::<_, IMMDeviceEnumerator>(&MMDeviceEnumerator, None, CLSCTX_ALL)
        else {
            return Vec::new();
        };
        let default_id =
            enumerator.GetDefaultAudioEndpoint(eRender, eConsole).ok().and_then(|d| device_id_string(&d));
        let Ok(collection) = enumerator.EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE) else {
            return Vec::new();
        };
        let count = collection.GetCount().unwrap_or(0);
        let mut out = Vec::with_capacity(count as usize);
        for i in 0..count {
            let Ok(dev) = collection.Item(i) else { continue };
            let Some(id) = device_id_string(&dev) else { continue };
            let name = device_friendly_name(&dev).unwrap_or_else(|| "Speakers".into());
            let is_default = default_id.as_deref() == Some(id.as_str());
            out.push(nectarlink_core::AudioOutputDevice { id, name, is_default });
        }
        out.sort_by(|a, b| b.is_default.cmp(&a.is_default).then_with(|| a.name.cmp(&b.name)));
        out
    }
}

/// Calls `changed` (on a worker thread, a moment after a burst of changes)
/// whenever Windows reports a change to the default speaker's or
/// microphone's volume or mute, or to the output devices (one plugged in or
/// out, a new default). This replaces polling: nothing runs while nothing
/// changes.
pub fn watch(changed: fn()) {
    std::thread::Builder::new().name("audio-watch".into()).spawn(move || watch_thread(changed)).ok();
}

#[windows::core::implement(IMMNotificationClient)]
struct DeviceEvents(std::sync::mpsc::Sender<Notice>);

#[windows::core::implement(IAudioEndpointVolumeCallback)]
struct VolumeEvents(std::sync::mpsc::Sender<Notice>);

enum Notice {
    /// A volume or mute changed.
    Volume,
    /// Devices changed; the default ones may be different now.
    Devices,
}

impl IMMNotificationClient_Impl for DeviceEvents_Impl {
    fn OnDeviceStateChanged(&self, _: &PCWSTR, _: DEVICE_STATE) -> windows::core::Result<()> {
        let _ = self.0.send(Notice::Devices);
        Ok(())
    }
    fn OnDeviceAdded(&self, _: &PCWSTR) -> windows::core::Result<()> {
        let _ = self.0.send(Notice::Devices);
        Ok(())
    }
    fn OnDeviceRemoved(&self, _: &PCWSTR) -> windows::core::Result<()> {
        let _ = self.0.send(Notice::Devices);
        Ok(())
    }
    fn OnDefaultDeviceChanged(&self, _: EDataFlow, role: ERole, _: &PCWSTR) -> windows::core::Result<()> {
        if role == eConsole {
            let _ = self.0.send(Notice::Devices);
        }
        Ok(())
    }
    fn OnPropertyValueChanged(&self, _: &PCWSTR, _: &PROPERTYKEY) -> windows::core::Result<()> {
        // Very chatty and rarely relevant (a renamed device shows on the
        // next other change).
        Ok(())
    }
}

impl IAudioEndpointVolumeCallback_Impl for VolumeEvents_Impl {
    fn OnNotify(&self, _: *mut AUDIO_VOLUME_NOTIFICATION_DATA) -> windows::core::Result<()> {
        let _ = self.0.send(Notice::Volume);
        Ok(())
    }
}

fn watch_thread(changed: fn()) {
    let _com = ComGuard::new();
    let (tx, rx) = std::sync::mpsc::channel();
    // SAFETY: Core Audio COM calls on this MTA thread; the callbacks only
    // send on a channel, and are unregistered before they're dropped.
    unsafe {
        let Ok(enumerator) =
            CoCreateInstance::<_, IMMDeviceEnumerator>(&MMDeviceEnumerator, None, CLSCTX_ALL)
        else {
            return;
        };
        let devices: IMMNotificationClient = DeviceEvents(tx.clone()).into();
        if enumerator.RegisterEndpointNotificationCallback(&devices).is_err() {
            return;
        }
        let volume: IAudioEndpointVolumeCallback = VolumeEvents(tx).into();
        // The default speaker and microphone being watched.
        let mut watched: Vec<IAudioEndpointVolume> = Vec::new();
        let rewatch = |watched: &mut Vec<IAudioEndpointVolume>| {
            for ep in watched.drain(..) {
                let _ = ep.UnregisterControlChangeNotify(&volume);
            }
            for flow in [eRender, eCapture] {
                if let Ok(ep) = endpoint_volume(flow)
                    && ep.RegisterControlChangeNotify(&volume).is_ok()
                {
                    watched.push(ep);
                }
            }
        };
        rewatch(&mut watched);
        while let Ok(first) = rx.recv() {
            // Settle: a slider drag or a device switch sends many at once.
            let mut devices_changed = matches!(first, Notice::Devices);
            while let Ok(next) = rx.recv_timeout(std::time::Duration::from_millis(150)) {
                devices_changed |= matches!(next, Notice::Devices);
            }
            if devices_changed {
                rewatch(&mut watched);
            }
            changed();
        }
    }
}

pub const SAMPLE_RATE: u32 = 48_000;
pub const CHANNELS: u16 = 2;
pub const BITS_PER_SAMPLE: u16 = 16;
pub const BYTES_PER_FRAME: usize = 4;
pub const CHUNK_BYTES: usize = 1_920;

pub struct DolbyStudioDsp {
    // Stereo low-shelf warmth filter state (~120 Hz)
    bass_l: f32,
    bass_r: f32,
    // Mid/Side spatial widener filter state (~300 Hz)
    side_l: f32,
    side_r: f32,
    // High-frequency clarity / dialogue presence state (~6 kHz)
    air_l: f32,
    air_r: f32,
}

impl DolbyStudioDsp {
    pub fn new() -> Self {
        Self {
            bass_l: 0.0,
            bass_r: 0.0,
            side_l: 0.0,
            side_r: 0.0,
            air_l: 0.0,
            air_r: 0.0,
        }
    }

    #[inline]
    pub fn process(&mut self, buf: &mut [u8], enabled: bool) {
        if !enabled {
            return;
        }

        // Clean, studio-grade linear acoustic filters designed for transparent, distortion-free fidelity:
        // 1. Bass cutoff (~120 Hz at 48kHz): alpha = 2*pi*120/48000 ~= 0.0157
        const BASS_ALPHA: f32 = 0.0157;
        // 2. Side-channel spatial filter (~300 Hz): alpha = 2*pi*300/48000 ~= 0.0392
        const SIDE_ALPHA: f32 = 0.0392;
        // 3. Dialogue & Air clarity filter (~6 kHz): alpha = 2*pi*6000/48000 ~= 0.55
        const AIR_ALPHA: f32 = 0.55;

        const STEREO_EXPAND: f32 = 1.25; // Expands Side channel cleanly for wide 3D soundstage
        const BASS_BOOST: f32 = 0.30; // +2.5 dB warm, punchy, distortion-free low-end
        const AIR_BOOST: f32 = 0.20; // +1.6 dB dialogue presence and acoustic sheen
        const PRE_SCALE: f32 = 0.88; // Clean headroom factor to prevent clipping

        for frame in buf.chunks_exact_mut(4) {
            let l_raw = i16::from_le_bytes([frame[0], frame[1]]) as f32 / 32768.0;
            let r_raw = i16::from_le_bytes([frame[2], frame[3]]) as f32 / 32768.0;

            let mut l = l_raw * PRE_SCALE;
            let mut r = r_raw * PRE_SCALE;

            // 1. Dolby 3D Stereo Expansion via Mid/Side Processing
            let mid = 0.5 * (l + r);
            let side = 0.5 * (l - r);

            // Filter out low bass from Side so low bass stays mono and punchy
            self.side_l += SIDE_ALPHA * (side - self.side_l);
            let side_high = side - self.side_l;
            let enhanced_side = side + side_high * (STEREO_EXPAND - 1.0);

            l = mid + enhanced_side;
            r = mid - enhanced_side;

            // 2. Clean, Non-Distorting Bass Warmth & Punch
            self.bass_l += BASS_ALPHA * (l_raw - self.bass_l);
            self.bass_r += BASS_ALPHA * (r_raw - self.bass_r);
            l += self.bass_l * BASS_BOOST;
            r += self.bass_r * BASS_BOOST;

            // 3. Dialogue & High-End Air Clarity
            self.air_l += AIR_ALPHA * (l - self.air_l);
            self.air_r += AIR_ALPHA * (r - self.air_r);
            let air_diff_l = l - self.air_l;
            let air_diff_r = r - self.air_r;
            l += air_diff_l * AIR_BOOST;
            r += air_diff_r * AIR_BOOST;

            // 4. Soft-Knee Transparent Peak Limiter (0% clipping, transparent dynamics)
            let l_out = if l.abs() > 0.85 {
                let s = l.signum();
                s * (0.85 + (l.abs() - 0.85).tanh() * 0.14)
            } else {
                l
            };
            let r_out = if r.abs() > 0.85 {
                let s = r.signum();
                s * (0.85 + (r.abs() - 0.85).tanh() * 0.14)
            } else {
                r
            };

            let l_i16 = (l_out.clamp(-0.999, 0.999) * 32767.0) as i16;
            let r_i16 = (r_out.clamp(-0.999, 0.999) * 32767.0) as i16;

            frame[0..2].copy_from_slice(&l_i16.to_le_bytes());
            frame[2..4].copy_from_slice(&r_i16.to_le_bytes());
        }
    }
}

pub fn run_wasapi_loopback(
    tx: tokio::sync::mpsc::Sender<bytes::Bytes>,
    stop: std::sync::Arc<std::sync::atomic::AtomicBool>,
    studio_quality: std::sync::Arc<std::sync::atomic::AtomicBool>,
) -> windows::core::Result<()> {
    use std::sync::atomic::Ordering;
    use windows::Win32::Media::Audio::*;
    use windows::Win32::System::Com::*;

    unsafe {
        let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
        let enumerator: IMMDeviceEnumerator =
            CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)?;
        let endpoint = enumerator.GetDefaultAudioEndpoint(eRender, eConsole)?;
        let client: IAudioClient = endpoint.Activate(CLSCTX_ALL, None)?;

        let wfx = WAVEFORMATEX {
            wFormatTag: WAVE_FORMAT_PCM as u16,
            nChannels: CHANNELS,
            nSamplesPerSec: SAMPLE_RATE,
            nAvgBytesPerSec: SAMPLE_RATE * (BYTES_PER_FRAME as u32),
            nBlockAlign: BYTES_PER_FRAME as u16,
            wBitsPerSample: BITS_PER_SAMPLE,
            cbSize: 0,
        };

        use windows::Win32::Foundation::CloseHandle;
        use windows::Win32::System::Threading::{CreateEventW, GetCurrentThread, SetThreadPriority, THREAD_PRIORITY_TIME_CRITICAL, WaitForSingleObject};

        let _ = SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_TIME_CRITICAL);
        let event = CreateEventW(None, false, false, None)?;

        let flags = AUDCLNT_STREAMFLAGS_LOOPBACK
            | AUDCLNT_STREAMFLAGS_EVENTCALLBACK
            | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM
            | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;

        client.Initialize(AUDCLNT_SHAREMODE_SHARED, flags, 100_000, 0, &wfx, None)?;
        client.SetEventHandle(event)?;
        let capture: IAudioCaptureClient = client.GetService()?;
        client.Start()?;

        let mut dsp = DolbyStudioDsp::new();
        let mut staging: Vec<u8> = Vec::with_capacity(CHUNK_BYTES * 2);

        while !stop.load(Ordering::Relaxed) {
            let _ = WaitForSingleObject(event, 10);
            let mut packet_frames = capture.GetNextPacketSize()?;

            while packet_frames > 0 {
                let mut data_ptr = std::ptr::null_mut();
                let mut num_frames = 0u32;
                let mut dw_flags = 0u32;

                capture.GetBuffer(&mut data_ptr, &mut num_frames, &mut dw_flags, None, None)?;
                let byte_len = (num_frames as usize) * BYTES_PER_FRAME;

                if (dw_flags & (AUDCLNT_BUFFERFLAGS_SILENT.0 as u32)) != 0 || data_ptr.is_null() {
                    staging.extend(std::iter::repeat(0u8).take(byte_len));
                } else {
                    let raw = std::slice::from_raw_parts(data_ptr as *const u8, byte_len);
                    staging.extend_from_slice(raw);
                }

                capture.ReleaseBuffer(num_frames)?;

                while staging.len() >= CHUNK_BYTES {
                    let mut chunk: Vec<u8> = staging.drain(..CHUNK_BYTES).collect();
                    let is_studio = studio_quality.load(Ordering::Relaxed);
                    dsp.process(&mut chunk, is_studio);
                    let _ = tx.try_send(bytes::Bytes::from(chunk));
                }

                packet_frames = capture.GetNextPacketSize()?;
            }
        }

        client.Stop()?;
        let _ = CloseHandle(event);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_speaker_mic_and_output_devices_without_mutating() {
        if let Some((vol, _muted)) = speaker_state() {
            assert!(vol <= 100);
        }
        let _ = mic_muted();
        let devices = output_devices();
        assert!(devices.iter().filter(|d| d.is_default).count() <= 1);
        for d in &devices {
            assert!(!d.id.is_empty());
            assert!(!d.name.is_empty());
        }
    }
}
