// Copyright 2026 The DiamondLauncher, Horizon, and BoardBridge Authors
// Licensed under the Apache License, Version 2.0 / MIT.

//! Offline authentication, UUIDv3 (`OfflinePlayer:<name>`) derivation, and
//! session-file generation matching `horizon-protocol/src/crypto.rs`,
//! `horizon-client/src/offline_name.rs`, and `launcher/src/account.rs`.

/// RFC 1321 MD5 digest, identical to `horizon_protocol::crypto::md5`.
pub fn md5(input: &[u8]) -> [u8; 16] {
    const S: [u32; 64] = [
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    ];
    const K: [u32; 64] = [
        0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee,
        0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
        0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be,
        0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
        0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa,
        0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
        0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed,
        0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
        0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c,
        0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
        0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05,
        0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
        0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039,
        0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
        0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1,
        0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391,
    ];
    let (mut a0, mut b0, mut c0, mut d0) = (
        0x6745_2301u32,
        0xefcd_ab89u32,
        0x98ba_dcfeu32,
        0x1032_5476u32,
    );
    let mut msg = input.to_vec();
    msg.push(0x80);
    while msg.len() % 64 != 56 {
        msg.push(0);
    }
    msg.extend_from_slice(&((input.len() as u64).wrapping_mul(8)).to_le_bytes());
    for chunk in msg.chunks(64) {
        let mut m = [0u32; 16];
        for (idx, w) in chunk.chunks(4).enumerate() {
            m[idx] = u32::from_le_bytes([w[0], w[1], w[2], w[3]]);
        }
        let (mut a, mut b, mut c, mut d) = (a0, b0, c0, d0);
        for i in 0..64 {
            let (f, g) = match i / 16 {
                0 => ((b & c) | (!b & d), i),
                1 => ((d & b) | (!d & c), (5 * i + 1) % 16),
                2 => (b ^ c ^ d, (3 * i + 5) % 16),
                _ => (c ^ (b | !d), (7 * i) % 16),
            };
            let f = f.wrapping_add(a).wrapping_add(K[i]).wrapping_add(m[g]);
            a = d;
            d = c;
            c = b;
            b = b.wrapping_add(f.rotate_left(S[i]));
        }
        a0 = a0.wrapping_add(a);
        b0 = b0.wrapping_add(b);
        c0 = c0.wrapping_add(c);
        d0 = d0.wrapping_add(d);
    }
    let mut out = [0u8; 16];
    for (i, v) in [a0, b0, c0, d0].iter().enumerate() {
        out[i * 4..i * 4 + 4].copy_from_slice(&v.to_le_bytes());
    }
    out
}

/// `UUID.nameUUIDFromBytes("OfflinePlayer:" + name)`: MD5, version 3, IETF variant.
/// Matches `horizon_protocol::crypto::offline_uuid` bit-for-bit.
pub fn offline_uuid(name: &str) -> u128 {
    let mut h = md5(format!("OfflinePlayer:{name}").as_bytes());
    h[6] = (h[6] & 0x0f) | 0x30;
    h[8] = (h[8] & 0x3f) | 0x80;
    u128::from_be_bytes(h)
}

/// Canonical dashed 8-4-4-4-12 UUID representation.
pub fn uuid_string(u: u128) -> String {
    let h = format!("{u:032x}");
    format!(
        "{}-{}-{}-{}-{}",
        &h[..8],
        &h[8..12],
        &h[12..16],
        &h[16..20],
        &h[20..]
    )
}

/// Undashed 32-char hex UUID representation (used in `accounts.json` and `session-*.json`).
pub fn uuid_undashed(u: u128) -> String {
    format!("{u:032x}")
}

/// Validates an offline player name per `launcher/src/account.rs::valid_offline_name`:
/// 3 to 16 ASCII letters, digits, or `_`.
pub fn is_valid_offline_name(name: &str) -> bool {
    let count = name.chars().count();
    (3..=16).contains(&count)
        && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
}

/// Generates a deterministic-format `Player{:05}` fallback name matching
/// `horizon-client/src/offline_name.rs::generate`.
pub fn generate_offline_name(seed: u64) -> String {
    format!("Player{:05}", seed % 100_000)
}

/// Represents a resolved offline player profile for DiamondLauncher.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct OfflineProfile {
    /// Player username (`3..=16` chars).
    pub name: String,
    /// Dashed UUIDv3 (`OfflinePlayer:<name>`).
    pub uuid_dashed: String,
    /// Undashed UUIDv3 (`OfflinePlayer:<name>`).
    pub uuid_undashed: String,
    /// Skin arm model (`"classic"` or `"slim"`).
    pub skin_model: String,
    /// Optional path to 64x64 or 64x32 skin PNG.
    pub skin_path: Option<String>,
    /// Optional path to 64x32 cape PNG.
    pub cape_path: Option<String>,
}

impl OfflineProfile {
    /// Constructs a validated offline profile or falls back to a generated `PlayerNNNNN`.
    pub fn from_name(name: &str, fallback_seed: u64) -> OfflineProfile {
        let trimmed = name.trim();
        let valid_name = if is_valid_offline_name(trimmed) {
            trimmed.to_string()
        } else {
            generate_offline_name(fallback_seed)
        };
        let raw_uuid = offline_uuid(&valid_name);
        OfflineProfile {
            name: valid_name,
            uuid_dashed: uuid_string(raw_uuid),
            uuid_undashed: uuid_undashed(raw_uuid),
            skin_model: "classic".to_string(),
            skin_path: None,
            cape_path: None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn md5_and_notch_offline_uuid_match_horizon_protocol() {
        assert_eq!(
            uuid_string(offline_uuid("Notch")),
            "b50ad385-829d-3141-a216-7e7d7539ba7f"
        );
        assert_eq!(
            uuid_undashed(offline_uuid("Notch")),
            "b50ad385829d3141a2167e7d7539ba7f"
        );
    }

    #[test]
    fn offline_name_validation_matches_horizon_launcher() {
        assert!(is_valid_offline_name("Alex_2"));
        assert!(is_valid_offline_name("Player00042"));
        assert!(!is_valid_offline_name("ab"));
        assert!(!is_valid_offline_name("ThisNameIsTooLong17"));
        assert!(!is_valid_offline_name("has space"));
        assert!(!is_valid_offline_name("été"));
        assert!(is_valid_offline_name("Diamond_MC"));
    }

    #[test]
    fn generated_names_are_valid_and_never_bare_player() {
        for seed in [0, 42, 99_999, 100_000, u64::MAX] {
            let name = generate_offline_name(seed);
            assert!(is_valid_offline_name(&name));
            assert_ne!(name, "Player");
            assert_eq!(name.len(), 11);
        }
        assert_eq!(generate_offline_name(42), "Player00042");
    }
}