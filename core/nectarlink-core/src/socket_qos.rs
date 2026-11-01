// SPDX-License-Identifier: MPL-2.0
//! WMM (Wi-Fi Multimedia) DSCP QoS Socket Tagging & Kernel Buffer Configuration
//! (`core/nectarlink-core/src/socket_qos.rs`).
//!
//! Why this fixes Wi-Fi router latency and throughput drops:
//! 1. **WMM Priority Queues (`AC_VO` and `AC_VI`)**:
//!    Wi-Fi routers divide airtime into four hardware queues:
//!    - `AC_BK` (Background)
//!    - `AC_BE` (Best Effort — default for untagged UDP sockets, long aggregation delay)
//!    - `AC_VI` (Video — `DSCP AF41 / CS4`, shorter contention window `CWmin=7`)
//!    - `AC_VO` (Voice — `DSCP EF / CS6`, immediate airtime `CWmin=3`, zero buffering)
//!    Tagging NectarLink's interactive control packets with `DSCP EF` (`0xB8`) and
//!    bulk/video streams with `DSCP AF41` (`0x88`) instructs the router to bypass
//!    the congested Best-Effort queue.
//! 2. **4 MiB Kernel UDP Socket Buffers (`SO_RCVBUF` / `SO_SNDBUF`)**:
//!    Prevents the OS kernel from silently dropping incoming or outgoing QUIC
//!    bursts when transferring at 100+ Mbps.

#![allow(unsafe_code)]
#![warn(missing_debug_implementations)]

/// Traffic class profile for NectarLink UDP sockets and streams.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TrafficQosClass {
    /// Interactive control, PC unlock, touchpad, keyboard, and audio (`DSCP EF = 46`, TOS `0xB8`).
    /// Maps to Wi-Fi Multimedia `AC_VO` (Voice queue — lowest latency).
    InteractiveLowLatency,
    /// Screen mirroring, phone webcam (`vcam`), and high-speed file transfers (`DSCP AF41 = 34`, TOS `0x88`).
    /// Maps to Wi-Fi Multimedia `AC_VI` (Video / prioritized high-throughput queue).
    HighThroughputMediaAndFiles,
}

impl TrafficQosClass {
    /// Returns the 8-bit IPv4 `IP_TOS` / IPv6 `IPV6_TCLASS` byte (DSCP shifted left by 2 bits).
    pub const fn ip_tos_byte(self) -> u8 {
        match self {
            Self::InteractiveLowLatency => 0xB8,      // DSCP 46 (EF) << 2
            Self::HighThroughputMediaAndFiles => 0x88, // DSCP 34 (AF41) << 2
        }
    }
}

/// Target kernel UDP socket buffer size (4 MiB) to absorb Wi-Fi hairpin bursts without packet drops.
pub const TARGET_UDP_BUFFER_BYTES: usize = 4 * 1024 * 1024;

/// Applies `IP_TOS` / `IPV6_TCLASS` WMM priority and 4 MiB `SO_RCVBUF` / `SO_SNDBUF`
/// on a bound `std::net::UdpSocket` (Linux / Android / macOS).
#[cfg(unix)]
pub fn configure_udp_socket_qos(
    socket: &std::net::UdpSocket,
    qos: TrafficQosClass,
) -> std::io::Result<()> {
    use std::os::fd::AsRawFd;
    let fd = socket.as_raw_fd();
    let tos: libc::c_int = libc::c_int::from(qos.ip_tos_byte());
    let buf_sz: libc::c_int = TARGET_UDP_BUFFER_BYTES as libc::c_int;

    // 1. Expand kernel receive and send buffers to 4 MiB
    // SAFETY: `fd` is a valid open socket descriptor owned by `socket`, and
    // `buf_sz` is a stack-allocated `c_int`.
    unsafe {
        let _ = libc::setsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_RCVBUF,
            (&raw const buf_sz).cast(),
            std::mem::size_of::<libc::c_int>() as libc::socklen_t,
        );
        let _ = libc::setsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_SNDBUF,
            (&raw const buf_sz).cast(),
            std::mem::size_of::<libc::c_int>() as libc::socklen_t,
        );

        // 2. Set IPv4 IP_TOS and IPv6 IPV6_TCLASS for Wi-Fi WMM hardware queue mapping
        let _ = libc::setsockopt(
            fd,
            libc::IPPROTO_IP,
            libc::IP_TOS,
            (&raw const tos).cast(),
            std::mem::size_of::<libc::c_int>() as libc::socklen_t,
        );
        let _ = libc::setsockopt(
            fd,
            libc::IPPROTO_IPV6,
            libc::IPV6_TCLASS,
            (&raw const tos).cast(),
            std::mem::size_of::<libc::c_int>() as libc::socklen_t,
        );
    }
    Ok(())
}

/// Applies 4 MiB `SO_RCVBUF` / `SO_SNDBUF` and DSCP marking on Windows (`windows = "0.62"`).
#[cfg(windows)]
pub fn configure_udp_socket_qos(
    socket: &std::net::UdpSocket,
    qos: TrafficQosClass,
) -> std::io::Result<()> {
    use std::os::windows::io::AsRawSocket;
    use windows::Win32::Networking::WinSock::{
        setsockopt, IPPROTO_IP, IPPROTO_IPV6, IPV6_TCLASS, IP_TOS, SOCKET, SOL_SOCKET, SO_RCVBUF,
        SO_SNDBUF,
    };

    let raw = SOCKET(socket.as_raw_socket() as usize);
    let buf_sz: i32 = TARGET_UDP_BUFFER_BYTES as i32;
    let tos: i32 = i32::from(qos.ip_tos_byte());

    // SAFETY: `raw` is a valid Winsock handle owned by `socket`.
    unsafe {
        let _ = setsockopt(raw, SOL_SOCKET, SO_RCVBUF, Some(&buf_sz.to_ne_bytes()));
        let _ = setsockopt(raw, SOL_SOCKET, SO_SNDBUF, Some(&buf_sz.to_ne_bytes()));
        let _ = setsockopt(raw, IPPROTO_IP.0, IP_TOS, Some(&tos.to_ne_bytes()));
        let _ = setsockopt(raw, IPPROTO_IPV6.0, IPV6_TCLASS, Some(&tos.to_ne_bytes()));
    }
    Ok(())
}