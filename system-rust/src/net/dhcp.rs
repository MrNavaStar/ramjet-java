use std::net::{IpAddr, Ipv4Addr, SocketAddrV4};

use anyhow::{anyhow, Context, Result};
use dhcproto::v4::{
    Decoder, Decodable, Encodable, Encoder, DhcpOption, Flags, Message, MessageType, Opcode,
    OptionCode,
};
use tokio::net::UdpSocket;
use tokio::time::{sleep, timeout, Duration, Instant};

use crate::net::interface::{get_mac, interface_up, set_address, set_default_route};

pub const RESOLV_CONF: &str = "/etc/resolv.conf";

const SERVER_PORT: u16 = 67;
const CLIENT_PORT: u16 = 68;

const DISCOVER_TIMEOUT: Duration = Duration::from_secs(3);
const DISCOVER_RETRIES: usize = 3;
const REQUEST_TIMEOUT: Duration = Duration::from_secs(3);
const REQUEST_RETRIES: usize = 3;
const DEFAULT_LEASE_TIME_SECS: u32 = 7200;
const RENEWAL_INTERVAL: Duration = Duration::from_secs(60);

#[derive(Debug, Clone, Default)]
struct NetworkInfo {
    gateway: Option<Ipv4Addr>,
    subnet: Option<Ipv4Addr>,
    dns1: Option<Ipv4Addr>,
    dns2: Option<Ipv4Addr>,
}

#[derive(Debug, Clone)]
struct Offer {
    server_id: Ipv4Addr,
    ip: Ipv4Addr,
}

#[derive(Debug, Clone)]
struct LeaseInfo {
    server_id: Ipv4Addr,
    ip: Ipv4Addr,
    lease_time_secs: u32,
    network_info: NetworkInfo,
}

pub async fn run(interface: &str) -> Result<(), anyhow::Error> {
    interface_up(interface).await?;

    let mac = get_mac(interface)?;
    let mut buf = [0u8; 1500];
    let socket = UdpSocket::bind(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, CLIENT_PORT))
        .await
        .context("bind DHCP client socket")?;
    socket.set_broadcast(true).context("enable DHCP broadcast")?;

    let mut lease = acquire(&socket, &mac, &mut buf).await?;

    if let Some(gateway) = lease.network_info.gateway {
        set_default_route(interface, IpAddr::from(gateway)).await?;
    }

    let prefix_len = lease
        .network_info
        .subnet
        .map(|subnet_mask| prefix_len_from_mask(subnet_mask))
        .unwrap_or(0);
    set_address(interface, IpAddr::from(lease.ip), prefix_len).await?;
    write_resolv_conf(lease.network_info.dns1, lease.network_info.dns2).await?;

    keep(&socket, &mac, &mut buf, &mut lease).await;

    Ok(())
}

async fn acquire(socket: &UdpSocket, mac: &[u8; 6], buf: &mut [u8]) -> Result<LeaseInfo> {
    let offer = discover(socket, mac, buf)
        .await?
        .ok_or_else(|| anyhow!("DHCP discovery failed"))?;

    let lease = request(socket, mac, offer.server_id, offer.ip, true, buf)
        .await?
        .ok_or_else(|| anyhow!("DHCP request was not acknowledged"))?;

    Ok(lease)
}

/// Sends a broadcast DISCOVER and waits for an OFFER from a DHCP server.
async fn discover(
    socket: &UdpSocket,
    mac: &[u8; 6],
    buf: &mut [u8],
) -> Result<Option<Offer>> {
    let start = Instant::now();

    for _ in 0..DISCOVER_RETRIES {
        let secs = start.elapsed().as_secs() as u16;
        let xid = random_xid();
        let msg = build_discover(mac, xid, secs);

        let bytes = encode_message(&msg)?;
        socket
            .send_to(
                &bytes,
                SocketAddrV4::new(Ipv4Addr::BROADCAST, SERVER_PORT),
            )
            .await?;

        if let Some(reply) = recv_message(
            socket,
            mac,
            buf,
            xid,
            MessageType::Offer,
            DISCOVER_TIMEOUT,
        )
        .await?
        {
            let server_id = reply
                .opts()
                .get(OptionCode::ServerIdentifier)
                .and_then(|option| match option {
                    DhcpOption::ServerIdentifier(server_id) => Some(*server_id),
                    _ => None,
                })
                .ok_or_else(|| anyhow!("DHCP offer missing server identifier"))?;

            let ip = reply.yiaddr();

            return Ok(Some(Offer { server_id, ip }));
        }
    }

    Ok(None)
}

/// Sends a REQUEST (broadcast or unicast to the DHCP server) and waits for an ACK.
async fn request(
    socket: &UdpSocket,
    mac: &[u8; 6],
    server_id: Ipv4Addr,
    ip: Ipv4Addr,
    broadcast: bool,
    buf: &mut [u8],
) -> Result<Option<LeaseInfo>> {
    let start = Instant::now();

    for _ in 0..REQUEST_RETRIES {
        let secs = start.elapsed().as_secs() as u16;
        let xid = random_xid();
        let msg = build_request(mac, xid, server_id, ip, broadcast, secs);

        let bytes = encode_message(&msg)?;
        let target = if broadcast {
            SocketAddrV4::new(Ipv4Addr::BROADCAST, SERVER_PORT)
        } else {
            SocketAddrV4::new(server_id, SERVER_PORT)
        };
        socket.send_to(&bytes, target).await?;

        if let Some(reply) = recv_message(
            socket,
            mac,
            buf,
            xid,
            MessageType::Ack,
            REQUEST_TIMEOUT,
        )
        .await?
        {
            let network_info = network_info_from_message(&reply);
            let lease_time_secs = reply
                .opts()
                .get(OptionCode::AddressLeaseTime)
                .and_then(|option| match option {
                    DhcpOption::AddressLeaseTime(lease_time_secs) => Some(*lease_time_secs),
                    _ => None,
                })
                .unwrap_or(DEFAULT_LEASE_TIME_SECS);

            return Ok(Some(LeaseInfo {
                server_id,
                ip: reply.yiaddr(),
                lease_time_secs,
                network_info,
            }));
        }
    }

    Ok(None)
}

/// Keeps the DHCP lease alive by renewing it when it is about to expire.
async fn keep(socket: &UdpSocket, mac: &[u8; 6], buf: &mut [u8], lease: &mut LeaseInfo) {
    let mut acquired = Instant::now();
    let renew_after = Duration::from_secs((lease.lease_time_secs / 3) as u64);

    loop {
        if acquired.elapsed() >= renew_after {
            let renewed = request(socket, mac, lease.server_id, lease.ip, false, buf)
                .await
                .unwrap_or(None);

            if let Some(new_lease) = renewed {
                acquired = Instant::now();
                lease.server_id = new_lease.server_id;
                lease.ip = new_lease.ip;
                lease.lease_time_secs = new_lease.lease_time_secs;
                lease.network_info = new_lease.network_info;
            } else {
                // Renewal failed; stop trying to keep the lease alive.
                break;
            }
        } else {
            sleep(RENEWAL_INTERVAL).await;
        }
    }
}

fn build_discover(mac: &[u8; 6], xid: u32, secs: u16) -> Message {
    let mut msg = Message::new_with_id(
        xid,
        Ipv4Addr::UNSPECIFIED,
        Ipv4Addr::UNSPECIFIED,
        Ipv4Addr::UNSPECIFIED,
        Ipv4Addr::UNSPECIFIED,
        mac,
    );
    msg.set_secs(secs);
    msg.set_flags(Flags::default().set_broadcast());
    msg.opts_mut().insert(DhcpOption::MessageType(MessageType::Discover));

    msg
}

fn build_request(
    mac: &[u8; 6],
    xid: u32,
    _server_id: Ipv4Addr,
    ip: Ipv4Addr,
    broadcast: bool,
    secs: u16,
) -> Message {
    let mut msg = Message::new_with_id(xid, ip, ip, Ipv4Addr::UNSPECIFIED, Ipv4Addr::UNSPECIFIED, mac);
    msg.set_secs(secs);
    msg.set_flags(if broadcast {
        Flags::default().set_broadcast()
    } else {
        Flags::default()
    });
    msg.opts_mut().insert(DhcpOption::MessageType(MessageType::Request));
    msg.opts_mut().insert(DhcpOption::RequestedIpAddress(ip));
    msg.opts_mut().insert(DhcpOption::ParameterRequestList(vec![
        OptionCode::Router,
        OptionCode::SubnetMask,
        OptionCode::DomainNameServer,
    ]));

    msg
}

fn encode_message(msg: &Message) -> Result<Vec<u8>> {
    let mut buffer = Vec::with_capacity(512);
    let mut encoder = Encoder::new(&mut buffer);
    msg.encode(&mut encoder)?;
    Ok(encoder.buffer_filled().to_vec())
}

async fn recv_message(
    socket: &UdpSocket,
    mac: &[u8; 6],
    buf: &mut [u8],
    xid: u32,
    expected: MessageType,
    timeout_duration: Duration,
) -> Result<Option<Message>> {
    let outcome = timeout(timeout_duration, async { socket.recv_from(buf).await }).await;

    let Ok(Ok((len, _addr))) = outcome else {
        // Timed out or failed - treat as no reply.
        return Ok(None);
    };

    let mut decoder = Decoder::new(&buf[..len]);
    let msg = Message::decode(&mut decoder)?;

    if msg.opcode() != Opcode::BootReply {
        return Ok(None);
    }
    if msg.hlen() != 6 {
        return Ok(None);
    }
    if msg.chaddr()[0..6] != mac[..] {
        return Ok(None);
    }
    if msg.chaddr()[6..16] != [0u8; 10][..] {
        return Ok(None);
    }
    if msg.xid() != xid {
        return Ok(None);
    }
    if !msg.opts().has_msg_type(expected) {
        return Ok(None);
    }

    Ok(Some(msg))
}

/// Converts a subnet mask into its prefix length (e.g. `255.255.255.0` -> `24`).
fn prefix_len_from_mask(subnet_mask: Ipv4Addr) -> u8 {
    let octets = subnet_mask.octets();
    let mask = u32::from_be_bytes([octets[0], octets[1], octets[2], octets[3]]);
    mask.leading_ones() as u8
}

fn network_info_from_message(msg: &Message) -> NetworkInfo {
    let gateway = msg
        .opts()
        .get(OptionCode::Router)
        .and_then(|option| match option {
            DhcpOption::Router(routers) => routers.first().copied(),
            _ => None,
        });

    let subnet = msg
        .opts()
        .get(OptionCode::SubnetMask)
        .and_then(|option| match option {
            DhcpOption::SubnetMask(subnet) => Some(*subnet),
            _ => None,
        });

    let dns_servers: Option<Vec<Ipv4Addr>> = msg
        .opts()
        .get(OptionCode::DomainNameServer)
        .and_then(|option| match option {
            DhcpOption::DomainNameServer(dns) => Some(dns.clone()),
            _ => None,
        });

    NetworkInfo {
        gateway,
        subnet,
        dns1: dns_servers.as_ref().and_then(|dns| dns.first().copied()),
        dns2: dns_servers.as_ref().and_then(|dns| dns.get(1).copied()),
    }
}

pub async fn write_resolv_conf(
    dns1: Option<Ipv4Addr>,
    dns2: Option<Ipv4Addr>,
) -> Result<()> {
    let mut contents = String::new();

    if let Some(dns1) = dns1 {
        contents.push_str(&format!("nameserver {dns1}\n"));
    }
    if let Some(dns2) = dns2 {
        contents.push_str(&format!("nameserver {dns2}\n"));
    }

    if contents.is_empty() {
        return Ok(());
    }

    std::fs::write(RESOLV_CONF, contents)?;

    Ok(())
}

fn random_xid() -> u32 {
    rand::random()
}
