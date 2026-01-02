<p align="center">
<img alt="GPExpansion" width=100% height=auto src="https://repository-images.githubusercontent.com/68339667/9b3f7c00-ce61-11ea-82d1-208eaa0606e8">
</p>

<h1 align="center">Extend GriefPrevention with rental signs, mailboxes, and more</h1>

<p align="center">
<a href="https://github.com/castledking/GPExpansion/releases"><img alt="Downloads" src="https://img.shields.io/badge/Downloads-green" height="70px"></a>
<a href="https://github.com/castledking/GPExpansion/wiki"><img alt="Docs" src="https://img.shields.io/badge/Docs-gray?logo=readthedocs&logoColor=white" height="70px"></a>
<a href="#support"><img alt="Get Help" src="https://img.shields.io/badge/Get%20Help-yellow?logo=amazoncloudwatch&logoColor=white" height="70px"></a>
</p>

GPExpansion adds powerful features to [GriefPrevention](https://github.com/GriefPrevention/GriefPrevention) including **rental signs**, **claim mailboxes**, **sign protection**, and more — all while maintaining the self-service philosophy.

Haven't heard of GriefPrevention 3D Subdivisions? Get it [here](https://github.com/castledking/GriefPrevention3D) to enable mailbox support. This is a fork that replaces the GriefPrevention jar.

---

## Supported Platforms
**Spigot, Paper, Purpur, and Folia**

Requires [GriefPrevention](https://github.com/GriefPrevention/GriefPrevention) and optionally [Vault](https://github.com/MilkBowl/Vault) for economy features.

Optionally, replace GriefPrevention with [GriefPrevention3D](https://github.com/castledking/GriefPrevention3D) for mailbox support.

---

## Features

### 🏠 Rental Signs
Allow claim owners to rent out their claims to other players using signs.
- Set rental prices and durations
- Automatic trust/untrust on rental start/expiry
- Supports both Vault economy and item-based payments

### 📬 Claim Mailboxes
Give each claim a mailbox where other players can deposit items.
- Owners have full access to retrieve items
- Non-owners can only deposit, not withdraw
- Storage warnings when mailbox is nearly full
- Purchasable via signs with configurable prices

### � Sell Signs
Allow claim owners to sell their claims to other players using signs.
- Set claim prices
- Automatic transfer of ownership of claim
- Supports both Vault economy and item-based payments

### 🔒 Sign Protection
Protect your rental and mailbox signs from unauthorized modification.
- Admin-only sign breaking for active rentals
- Automatic cleanup on sign removal

### �📋 Claim Management
- `/claim name` - Set claim name
- `/claim ban` - Ban players from your claims
- `/claim unban` - Unban players from your claims
- `/mailbox` - Manage your mailboxes

---

## Sign Formats

### Rental Signs
```
[rent claim]
<id>
<ecoType>
<ecoAmt>;<renewalTime>;<maxTime>
```
- `<id>` - Do `/claimlist` to get this
- `<ecoType>` - Accepts `money`, `claimblocks`, `exp` or `item`
- `<ecoAmt>` - The cost per renewal period
- `<renewalTime>` - Duration of each rental period
- `<maxTime>` - Maximum total rental duration

> **Note:** Hold the item you wish to set in your offhand while creating the sign.

### Sell Signs
```
[sell claim]
<id>
<ecoType>
<ecoAmt>
```
- `<id>` - Do `/claimlist` to get this
- `<ecoType>` - Accepts `money`, `claimblocks`, `exp` or `item`
- `<ecoAmt>` - The sale price

> **Note:** Hold the item you wish to set in your offhand while creating the sign.

### Mailbox Signs
```
[mailbox]
<id>
<ecoType>
<ecoAmt>
```
- `<id>` - Do `/claimlist` to get this
- `<ecoType>` - Accepts `money`, `claimblocks`, `exp` or `item`
- `<ecoAmt>` - The mailbox purchase price

> **Note:** You need to create a 1x1x1 3D subdivision in your claim with a supported container type (barrel, hopper, chest, etc.).

---

## Installation

1. Download the latest release
2. Place `GPExpansion.jar` in your `plugins` folder
3. Ensure GriefPrevention is installed
4. (Optional) Install Vault for economy support
5. Restart your server
6. Configure `plugins/GPExpansion/config.yml`

---

## Configuration

The plugin creates a `config.yml` with sensible defaults. Key options include:

```yaml
# Debug settings
debug:
  # Enable debug logging for GPBridge to troubleshoot claim/subclaim detection
  enabled: true
  
# Default limits for sign creation (can be overridden by permissions)
defaults:
  # Default maximum number of sell signs a player can create
  max-sell-signs: 5
  # Default maximum number of rent signs a player can create
  max-rent-signs: 5
  # Default maximum number of mailbox signs a player can create
  max-mailbox-signs: 5
  
# Permission tracking settings
permission-tracking:
  # Enable tracking of player permissions for sign limits
  enabled: true
  # How often to check for permission changes (in minutes)
  check-interval: 5
```

---

## Permissions

| Permission | Description |
|------------|-------------|
| `griefprevention.sign.create.rent` | Create rental signs |
| `griefprevention.sign.create.mailbox` | Create mailbox signs |
| `griefprevention.sign.create.sell` | Create sell signs |
| `griefprevention.sign.use.rent` | Use rental signs |
| `griefprevention.sign.use.mailbox` | Use mailbox signs |
| `griefprevention.sign.use.sell` | Use sell signs |
| `griefprevention.admin` | Admin commands and sign management |

---

## Support

- [Issue Tracker](https://github.com/castledking/GPExpansion/issues) - Report bugs or problems
- [Discussions](https://github.com/castledking/GPExpansion/discussions) - Feature requests and questions
- [Wiki](https://github.com/castledking/GPExpansion/wiki) - Detailed documentation and guides
- [Discord](https://discord.gg/pCKdCX6nYr) - Join our community

---

## Building from Source

```bash
git clone https://github.com/castledking/GPExpansion.git
cd GPExpansion
mvn clean install
```

The compiled jar will be in `target/`.

---

<p align="center">
<i>Built to extend GriefPrevention with love ❤️</i>
</p>
