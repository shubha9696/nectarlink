// SPDX-License-Identifier: GPL-3.0-or-later

//! The phone's sound on this PC without a mirror window

//! (`MirrorStart::sound_only`, docs/protocol/mirror.md): what the phone plays

//! comes out of the PC's speakers or headphones. Opening the phone's mirror

//! window takes over, since that brings the sound too.



use std::{

    sync::{Arc, Mutex},

    time::Duration,

};



use nectarlink_core::{

    DeviceId, Error, FeatureState, LinkState, MIRROR_SCREEN, MirrorAudioConfig, MirrorConfig, MirrorSink,

    MirrorStart,

};



use crate::{bridge::app::show_message, core_host, i18n::tr, state::Changes, win::audio_out::Player};



/// How long the phone's prompt may go unanswered.

const ASK_TIMEOUT: Duration = Duration::from_secs(120);



#[derive(Debug, Clone, Copy, PartialEq, Eq)]

pub enum Phase {

    /// Asked; the phone may be showing its prompt.

    Asking,

    /// Its sound is playing here.

    Playing,

}



/// The one phone whose sound plays here, if any.

static ACTIVE: Mutex<Option<(DeviceId, Phase)>> = Mutex::new(None);



pub fn active() -> Option<(DeviceId, Phase)> {

    *ACTIVE.lock().unwrap_or_else(|e| e.into_inner())

}



fn set_active(next: Option<(DeviceId, Phase)>) {

    let changed = {

        let mut active = ACTIVE.lock().unwrap_or_else(|e| e.into_inner());

        let changed = *active != next;

        *active = next;

        changed

    };

    if changed {

        core_host::host().hub.changed(Changes::MEDIA);

    }

}



/// The phone to play: the first one online that can share its sound alone.

pub fn phone() -> Option<(DeviceId, String)> {
    core_host::host().hub.read(|s| {
        s.devices
            .iter()
            .filter(|d| matches!(d.link, LinkState::Online { .. }))
            .find(|d| {
                s.matrices.get(&d.id).and_then(|m| m.state("mirroring.sound"))
                    == Some(FeatureState::Available)
            })
            .or_else(|| {
                s.devices
                    .iter()
                    .find(|d| matches!(d.link, LinkState::Online { .. }))
            })
            .map(|d| (d.id, d.info.name.clone()))
    })
}



/// "off", "asking" (waiting for the phone) or "playing".

pub fn state() -> &'static str {

    match active() {

        None => "off",

        Some((_, Phase::Asking)) => "asking",

        Some((_, Phase::Playing)) => "playing",

    }

}



/// Starts or stops playing the phone's sound here.
pub fn set_on(on: bool) {
    if !on {
        stop_active();
        return;
    }
    let (Some((device, name)), Some(node)) = (phone(), core_host::node()) else { return };
    // Reset any previous or stale active state so a toggle click always triggers cleanly:
    if let Some((active_dev, _)) = active() {
        if active_dev != device {
            stop(active_dev);
        } else {
            set_active(None);
        }
    }
    set_active(Some((device, Phase::Asking)));

    core_host::spawn(async move {

        let options = MirrorStart {

            max_size: 0,

            fps: 0,

            bitrate: 0,

            audio: true,

            session: MIRROR_SCREEN,

            app: None,

            stay_awake: false,

            screen_off: false,

            watch: None,

            sound_only: true,

        };

        if let Err(e) = node.mirror_start(device, options).await {

            set_active(None);

            show_message(match e {

                Error::Denied => tr!("PhoneSound", "Mirroring is turned off for this phone."),

                Error::Unsupported => {

                    tr!("PhoneSound", "Update NectarLink on %1 to play its sound here.", name)

                }

                Error::Offline | Error::NotPaired => tr!("PhoneSound", "The phone isn't connected."),

                // The phone couldn't show its request (notifications off, say).

                e => {

                    tracing::warn!(error = %e, "the phone couldn't share its sound");

                    tr!(

                        "PhoneSound",

                        "%1 couldn't ask to share its sound. Check that NectarLink can show notifications on the phone.",

                        name

                    )

                }

            });

            return;

        }

        // Nobody answered the phone's prompt: stop waiting.

        tokio::time::sleep(ASK_TIMEOUT).await;

        if active() == Some((device, Phase::Asking)) {

            stop(device);

        }

    });

}



/// Stops it for `device` and tells the phone.
fn stop(device: DeviceId) {
    set_active(None);
    let Some(node) = core_host::node() else { return };
    core_host::spawn(async move { node.mirror_stop(device, MIRROR_SCREEN).await });
}

/// Stops any active phone sound session.
pub fn stop_active() {
    if let Some((device, _)) = active() {
        stop(device);
    } else {
        set_active(None);
    }
}

/// The mirror window is about to ask `device` for its screen, which brings
/// the sound too: the phone shares one or the other, so this one ends first.
pub fn stop_for_window(device: DeviceId) {
    if active().is_some_and(|(d, _)| d == device) {
        stop(device);
    }
}



/// The sink for `device`'s sound stream while it plays here without a window.

pub fn sink(device: &DeviceId) -> Option<Arc<dyn MirrorSink>> {

    let matches_active = active().is_some_and(|(d, _)| d == *device);

    let paired = core_host::host().hub.read(|s| s.device(device).is_some());

    if matches_active || paired {

        if !matches_active {

            set_active(Some((*device, Phase::Playing)));

        }

        Some(Arc::new(Sink { device: *device, player: Mutex::new(None) }) as Arc<dyn MirrorSink>)

    } else {

        None

    }

}



/// Plays the sound stream; there's no picture with it.

struct Sink {

    device: DeviceId,

    player: Mutex<Option<Player>>,

}



impl MirrorSink for Sink {

    fn config(&self, _config: MirrorConfig) {}



    fn packet(&self, _keyframe: bool, _time_us: u64, _data: Vec<u8>) {}



    fn ended(&self) {}



    fn audio_config(&self, config: MirrorAudioConfig) {

        // A new format: a new player (the old one ends when dropped).

        let player = Player::start(config.rate, u16::from(config.channels))

            .inspect_err(|e| tracing::warn!(error = %e, "can't play the phone's sound"))

            .ok();

        let playing = player.is_some();

        *self.player.lock().unwrap_or_else(|e| e.into_inner()) = player;

        if playing && active().is_some_and(|(d, _)| d == self.device) {

            set_active(Some((self.device, Phase::Playing)));

        }

    }



    fn audio(&self, _time_us: u64, data: Vec<u8>) {

        // The mirror window's mute is the phone's sound on this PC.

        if crate::mirror::muted() || crate::mirror::audio_mode(&self.device) == "phone" {

            return;

        }

        if let Some(player) = self.player.lock().unwrap_or_else(|e| e.into_inner()).as_ref() {

            player.push(data);

        }

    }



    fn audio_ended(&self) {
        self.player.lock().unwrap_or_else(|e| e.into_inner()).take();
        // The phone stopped, or the connection dropped.
        if active().is_some_and(|(d, _)| d == self.device) {
            set_active(None);
        }
    }
}

impl Drop for Sink {
    fn drop(&mut self) {
        self.player.lock().unwrap_or_else(|e| e.into_inner()).take();
        if active().is_some_and(|(d, _)| d == self.device) {
            set_active(None);
        }
    }
}



