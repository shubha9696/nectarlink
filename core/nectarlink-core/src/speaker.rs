// SPDX-License-Identifier: MPL-2.0
use anyhow::Result;
use bytes::Bytes;
use iroh::endpoint::{RecvStream, SendStream};
use tokio::sync::mpsc::{Receiver, Sender};

pub async fn send_pc_audio_stream(
    mut stream: SendStream,
    mut rx: Receiver<Bytes>,
) -> Result<()> {
    let header = [0x80u8, 0xBB, 0x00, 0x00, 0x02, 0x10]; // 48000 Hz, 2 ch, 16-bit
    stream.write_all(&header).await?;

    while let Some(frame) = rx.recv().await {
        let len = (frame.len() as u16).to_le_bytes();
        let mut packet = Vec::with_capacity(2 + frame.len());
        packet.extend_from_slice(&len);
        packet.extend_from_slice(&frame);
        stream.write_all(&packet).await?;
    }

    stream.finish()?;
    Ok(())
}

pub async fn recv_pc_audio_stream(
    mut stream: RecvStream,
    tx: Sender<Vec<u8>>,
) -> Result<()> {
    let mut header = [0u8; 6];
    stream.read_exact(&mut header).await?;

    let mut len_buf = [0u8; 2];
    loop {
        if stream.read_exact(&mut len_buf).await.is_err() {
            break;
        }
        let frame_len = u16::from_le_bytes(len_buf) as usize;
        let mut pcm = vec![0u8; frame_len];
        stream.read_exact(&mut pcm).await?;

        let _ = tx.try_send(pcm);
    }
    Ok(())
}

