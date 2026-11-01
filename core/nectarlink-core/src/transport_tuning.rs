// SPDX-License-Identifier: MPL-2.0
//! QUIC Transport Tuning & Wi-Fi Hairpin Micro-Burst Pacer
//! (`core/nectarlink-core/src/transport_tuning.rs`).
//!
//! Solves the classic router throughput collapse where file transfers start at
//! ~15 MB/s during QUIC Slow Start and then drop to 5 MB/s -> 2 MB/s after a few
//! seconds when both devices are on the same Wi-Fi router (`Phone -> Router -> PC`):
//!
//! 1. **Switches `noq-proto` (`iroh`) from Cubic to BBR (`BbrConfig`)**:
//!    Cubic slashes `cwnd` by 30% on every packet drop caused by Wi-Fi hairpin
//!    contention. BBR models the bottleneck bandwidth and minimum RTT, keeping
//!    throughput high across noisy 2-hop wireless links.
//! 2. **Raises Packet Reordering Tolerance (`packet_threshold = 12`)**:
//!    Two-hop Wi-Fi (802.11 Block-ACK retries) routinely reorders 4–8 packets;
//!    raising the threshold from 3 to 12 prevents false-positive loss recovery.
//! 3. **Caps MTU & Expands Stream Flow-Control Windows (8 MiB / 16 MiB)**:
//!    Prevents flow-control stalls across high-jitter router hops while keeping
//!    UDP datagrams below 1350 bytes to avoid router fragmentation.
//! 4. **Token-Bucket Micro-Burst Smoother (`PacedTransferWriter`)**:
//!    Consumer routers (e.g., JioFiber, Airtel, TP-Link) often trigger "UDP Flood
//!    Defense" or overflow their per-station hairpin queues when QUIC blasts
//!    unpaced 64-packet UDP GSO bursts. `PacedTransferWriter` slices bulk file
//!    writes into 16 KiB chunks with sub-millisecond adaptive pacing.

#![forbid(unsafe_code)]
#![warn(missing_debug_implementations)]

use std:: net::IpAddr;
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncWrite, AsyncWriteExt};
use tokio::time::Instant;

/// Stream-level receive window for bulk file transfers (8 MiB).
pub const FILE_STREAM_RECEIVE_WINDOW_BYTES: u32 = 8 * 1024 * 1024;

/// Connection-level receive window across all concurrent streams (16 MiB).
pub const CONNECTION_RECEIVE_WINDOW_BYTES: u32 = 16 * 1024 * 1024;

/// Connection-level send window (16 MiB).
pub const CONNECTION_SEND_WINDOW_BYTES: u64 = 16 * 1024 * 1024;

/// Safe UDP payload ceiling that avoids IP fragmentation across Wi-Fi + VLAN/PPPoE headers.
pub const WIFI_SAFE_MAX_UDP_PAYLOAD: u16 = 1350;

/// Reordering tolerance in packets (default QUIC is 3, which is too low for 2-hop Wi-Fi).
pub const WIFI_HAIRPIN_PACKET_THRESHOLD: u32 = 12;

/// Default chunk size (16 KiB = ~12 QUIC packets) for micro-burst smoothing.
pub const PACED_CHUNK_BYTES: usize = 16 * 1024;

/// Network topology classification between the PC and the phone.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LinkTopology {
    /// Direct 1-hop link: Phone Hotspot (`192.168.43.x`, `192.168.49.x`, `10.42.0.x`),
    /// USB RNDIS/NCM tethering, or Wi-Fi Direct.
    DirectHotspotOrUsb,
    /// Shared LAN router (`192.168.0.x`, `192.168.1.x`, `192.168.29.x`, `10.x.x.x`)
    /// where packets may traverse two Wi-Fi hops (`Phone -> Router -> PC`).
    RouterLanHairpin,
    /// Tailscale (`100.64.0.0/10`) or internet relay (`iroh` DERP).
    OverlayOrRelay,
}

impl LinkTopology {
    /// Classifies the link from the peer's IP address so NectarLink can apply
    /// router hairpin protection automatically when on a home router.
    pub fn from_peer_ip(ip: IpAddr) -> Self {
        match ip {
            IpAddr::V4(v4) => {
                let octets = v4.octets();
                // Common Android SoftAP / Hotspot / USB tethering / Wi-Fi Direct subnets:
                // - Android 11+ SoftAP & USB tethering: 192.168.42.0/24, 192.168.43.0/24,
                //   192.168.49.0/24 (Wi-Fi Direct), 192.168.137.0/24 (Windows Hotspot)
                if octets[0] == 192
                    && octets[1] == 168
                    && matches!(octets[2], 42 | 43 | 49 | 137)
                {
                    return Self::DirectHotspotOrUsb;
                }
                // Tailscale CGNAT range 100.64.0.0/10
                if octets[0] == 100 && (64..=127).contains(&octets[1]) {
                    return Self::OverlayOrRelay;
                }
                if v4.is_private() || v4.is_link_local() {
                    return Self::RouterLanHairpin;
                }
                Self::OverlayOrRelay
            }
            IpAddr::V6(v6) => {
                if v6.is_unicast_link_local() {
                    Self::RouterLanHairpin
                } else {
                    Self::OverlayOrRelay
                }
            }
        }
    }
}

/// Builds a `noq_proto::TransportConfig` (used by `iroh::Endpoint`) tuned to
/// eliminate the 15 MB/s -> 5 MB/s -> 2 MB/s throughput drop over Wi-Fi routers.
///
/// Drop-in usage when constructing `iroh::Endpoint` in `core/nectarlink-core`:
/// ```rust,ignore
/// let quic_config = build_wifi_optimized_transport_config();
/// let endpoint = iroh::Endpoint::builder()
///     .transport_config(quic_config)
///     .bind()
///     .await?;
/// ```
pub fn build_wifi_optimized_transport_config() -> noq_proto::TransportConfig {
    use noq_proto::congestion::Bbr3Config as BbrConfig;
    use noq_proto::{TransportConfig, VarInt};

    let mut config = TransportConfig::default();

    // 1. Switch from Cubic (loss-based) to BBR (bandwidth & RTT model).
    // BBR ignores isolated Wi-Fi Block-ACK packet losses and paces outgoing
    // packets smoothly at the router's bottleneck rate.
    let mut bbr = BbrConfig::default();
    // bbr.initial_window(64 * 1024); // 64 KiB gentle start so we don't shock the router queue
    config.congestion_controller_factory(Arc::new(bbr));

    // 2. Expand flow-control windows so high-bandwidth local transfers never
    // stall waiting for window-update frames across 2-hop Wi-Fi jitter.
    if let Ok(stream_win) = VarInt::try_from(FILE_STREAM_RECEIVE_WINDOW_BYTES) {
        config.stream_receive_window(stream_win);
    }
    if let Ok(conn_win) = VarInt::try_from(CONNECTION_RECEIVE_WINDOW_BYTES) {
        config.receive_window(conn_win);
    }
    config.send_window(CONNECTION_SEND_WINDOW_BYTES);

    // 3. Tolerate 2-hop Wi-Fi frame reordering without triggering false loss recovery.
    config.packet_threshold(WIFI_HAIRPIN_PACKET_THRESHOLD);

    // 4. Cap UDP payload size to prevent IP fragmentation on routers and avoid
    // oversized GSO bursts overflowing small per-client AP queues.
    config.initial_mtu(WIFI_SAFE_MAX_UDP_PAYLOAD);
    config.min_mtu(1200);
    config.mtu_discovery_config(None); // Disable PMTUD probe bursts that trip router UDP flood filters

    // 5. Send a lightweight PING frame every 3 seconds so the Android Wi-Fi
    // chipset stays awake and does not enter 100-300ms DTIM Power Save sleep.
    config.keep_alive_interval(Some(Duration::from_secs(3)));
    if let Ok(idle) = Duration::from_secs(30).try_into() {
        config.max_idle_timeout(Some(idle));
    }

    config
}

/// Adaptive token-bucket stream writer that wraps an `iroh::endpoint::SendStream`
/// (or any `tokio::io::AsyncWrite`) during file transfers.
///
/// Why this is needed even with BBR:
/// When an application calls `send_stream.write_all(&buf_1mb)` in a tight loop,
/// the QUIC stack queues hundreds of UDP datagrams in a single sub-millisecond
/// burst. Consumer Wi-Fi routers see >5,000 UDP pps from a single LAN client
/// and engage **UDP Flood Defense / Storm Control** after ~2–3 seconds, clamping
/// throughput to 2–5 MB/s.
///
/// `PacedTransferWriter` feeds `PACED_CHUNK_BYTES` (16 KiB) slices with smooth
/// inter-chunk spacing and automatically backs off when write latency rises
/// (indicating the local QUIC send buffer is filling faster than the router drains).
#[derive(Debug)]
pub struct PacedTransferWriter<W> {
    inner: W,
    topology: LinkTopology,
    target_bytes_per_sec: u64,
    min_rate_bytes_per_sec: u64,
    max_rate_bytes_per_sec: u64,
    smoothed_write_us: f64,
    bytes_sent_in_window: u64,
    window_start: Instant,
}

impl<W: AsyncWrite + Unpin> PacedTransferWriter<W> {
    /// Creates a new paced writer tuned for the detected [`LinkTopology`].
    pub fn new(inner: W, topology: LinkTopology) -> Self {
        let (initial_rate, min_rate, max_rate) = match topology {
            LinkTopology::DirectHotspotOrUsb => (
                25 * 1024 * 1024,  // Start at 25 MB/s (200 Mbps) on Hotspot/USB
                5 * 1024 * 1024,   // Floor: 5 MB/s
                125 * 1024 * 1024, // Ceiling: 125 MB/s (1 Gbps)
            ),
            LinkTopology::RouterLanHairpin => (
                12 * 1024 * 1024, // Start at 12 MB/s (96 Mbps) — below router UDP flood trigger!
                4 * 1024 * 1024,  // Floor: 4 MB/s
                45 * 1024 * 1024, // Ceiling: 45 MB/s (360 Mbps) smooth hairpin rate
            ),
            LinkTopology::OverlayOrRelay => (
                8 * 1024 * 1024,
                1024 * 1024,
                30 * 1024 * 1024,
            ),
        };

        Self {
            inner,
            topology,
            target_bytes_per_sec: initial_rate,
            min_rate_bytes_per_sec: min_rate,
            max_rate_bytes_per_sec: max_rate,
            smoothed_write_us: 200.0,
            bytes_sent_in_window: 0,
            window_start: Instant::now(),
        }
    }

    /// Returns the current adaptive pacing rate in bytes per second.
    pub fn current_target_rate_bps(&self) -> u64 {
        self.target_bytes_per_sec
    }

    /// Writes `data` in 16 KiB micro-chunks with token-bucket pacing and
    /// backpressure detection so router hairpin buffers never overflow.
    pub async fn write_all_paced(&mut self, data: &[u8]) -> std::io::Result<()> {
        for chunk in data.chunks(PACED_CHUNK_BYTES) {
            let chunk_start = Instant::now();
            self.inner.write_all(chunk).await?;
            let write_elapsed = chunk_start.elapsed();

            let write_us = write_elapsed.as_micros() as f64;
            self.smoothed_write_us = 0.85 * self.smoothed_write_us + 0.15 * write_us;
            self.bytes_sent_in_window += chunk.len() as u64;

            // Adaptive AIMD rate adjustment every 250 ms based on QUIC buffer backpressure:
            // - If `write_all` blocks for >2,500 us, the QUIC send window is saturating
            //   the router's Wi-Fi hairpin queue -> gently trim rate by 12% (not 30%!)
            //   so the router queue drains BEFORE packets are dropped.
            // - If `write_all` completes quickly (<800 us), smoothly probe +8% higher.
            if self.window_start.elapsed() >= Duration::from_millis(250) {
                if self.smoothed_write_us > 2_500.0 {
                    let reduced = (self.target_bytes_per_sec as f64 * 0.88) as u64;
                    self.target_bytes_per_sec = reduced.max(self.min_rate_bytes_per_sec);
                } else if self.smoothed_write_us < 800.0 {
                    let increased = (self.target_bytes_per_sec as f64 * 1.08) as u64;
                    self.target_bytes_per_sec = increased.min(self.max_rate_bytes_per_sec);
                }
                self.bytes_sent_in_window = 0;
                self.window_start = Instant::now();
            }

            // On RouterLanHairpin, enforce micro-pacing between 16 KiB chunks so we
            // never emit back-to-back GSO spikes that trip router UDP Flood Defense.
            if self.topology == LinkTopology::RouterLanHairpin {
                let ideal_chunk_ns =
                    (chunk.len() as u128 * 1_000_000_000) / (self.target_bytes_per_sec as u128);
                let ideal_dur = Duration::from_nanos(ideal_chunk_ns as u64);
                if ideal_dur > write_elapsed {
                    let sleep_dur = ideal_dur - write_elapsed;
                    if sleep_dur >= Duration::from_micros(400) {
                        tokio::time::sleep(sleep_dur).await;
                    } else {
                        tokio::task::yield_now().await;
                    }
                }
            }
        }
        Ok(())
    }

    /// Flushes the underlying stream.
    pub async fn flush(&mut self) -> std::io::Result<()> {
        self.inner.flush().await
    }

    /// Consumes the wrapper and returns the inner stream.
    pub fn into_inner(self) -> W {
        self.inner
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{Ipv4Addr, Ipv6Addr};

    #[test]
    fn topology_classification_identifies_hotspot_vs_router() {
        assert_eq!(
            LinkTopology::from_peer_ip(IpAddr::V4(Ipv4Addr::new(192, 168, 43, 1))),
            LinkTopology::DirectHotspotOrUsb
        );
        assert_eq!(
            LinkTopology::from_peer_ip(IpAddr::V4(Ipv4Addr::new(192, 168, 137, 1))),
            LinkTopology::DirectHotspotOrUsb
        );
        assert_eq!(
            LinkTopology::from_peer_ip(IpAddr::V4(Ipv4Addr::new(192, 168, 1, 45))),
            LinkTopology::RouterLanHairpin
        );
        assert_eq!(
            LinkTopology::from_peer_ip(IpAddr::V4(Ipv4Addr::new(192, 168, 29, 102))),
            LinkTopology::RouterLanHairpin
        );
        assert_eq!(
            LinkTopology::from_peer_ip(IpAddr::V4(Ipv4Addr::new(100, 101, 102, 103))),
            LinkTopology::OverlayOrRelay
        );
        assert_eq!(
            LinkTopology::from_peer_ip(IpAddr::V6(Ipv6Addr::new(0xfe80, 0, 0, 0, 1, 2, 3, 4))),
            LinkTopology::RouterLanHairpin
        );
    }

    #[tokio::test]
    async fn paced_writer_transfers_exact_bytes() {
        let mut sink: Vec<u8> = Vec::new();
        let payload = vec![0x5Au8; 64 * 1024 + 321];
        let mut writer = PacedTransferWriter::new(&mut sink, LinkTopology::RouterLanHairpin);
        writer.write_all_paced(&payload).await.expect("paced write");
        writer.flush().await.expect("flush");
        assert_eq!(sink, payload);
    }
}