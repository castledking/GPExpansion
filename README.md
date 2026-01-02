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
- Supports Vault economy, experience, claim blocks, and item-based payments
- Interactive setup wizard with `/rentclaim` command

### 📬 Claim Mailboxes
Give each claim a mailbox where other players can deposit items.
- Owners have full access to retrieve items
- Non-owners can only deposit, not withdraw
- Storage warnings when mailbox is nearly full
- Purchasable via signs with configurable prices
- Interactive setup wizard with `/mailbox` command

### 🏷️ Sell Signs
Allow claim owners to sell their claims to other players using signs.
- Set claim prices
- Automatic transfer of ownership of claim
- Supports Vault economy, experience, claim blocks, and item-based payments
- Interactive setup wizard with `/sellclaim` command

### 🔒 Sign Protection
Protect your rental and mailbox signs from unauthorized modification.
- Admin-only sign breaking for active rentals
- Automatic cleanup on sign removal

### 📋 Claim Management
- `/claim name` - Set claim name
- `/claim ban` - Ban players from your claims
- `/claim unban` - Unban players from your claims
- `/claim info` - View detailed claim information
- `/mailbox` - Manage your mailboxes
- `/gpx reload` - Reload configuration and language files
- `/gpx max` - Manage player sign creation limits

---

## Sign Formats

All sign types support flexible formatting where `<ecoType>` is optional and defaults to `money` if only a number is provided.

### Rental Signs

**Short Format** (place sign inside claim):
```
[rent]
<renewTime>
<ecoAmt>
```
OR with explicit eco type:
```
[rent]
<renewTime>
<ecoType>
<ecoAmt>
```

**Condensed Format** (outside claim):
```
[rent]
<id>;<ecoAmt>;<renewTime>
```
OR with max time:
```
[rent]
<id>;<ecoAmt>;<renewTime>;<maxTime>
```

- `<id>` - Do `/claimlist` to get this (not needed for short format)
- `<ecoType>` - Accepts `money`, `claimblocks`, `exp` or `item` (optional, defaults to `money`)
- `<ecoAmt>` - The cost per renewal period
- `<renewTime>` - Duration of each rental period (e.g., `1w`, `7d`, `24h`)
- `<maxTime>` - Maximum total rental duration (optional, defaults to `<renewTime>`)

> **Note:** Hold the item you wish to set in your offhand while creating an item-based sign.

### Sell/Buy Signs

**Short Format** (place sign inside claim):
```
[sell]
<ecoAmt>
```
OR
```
[sell]
<ecoType>
<ecoAmt>
```

**Condensed Format** (outside claim):
```
[sell]
<id>;<ecoAmt>
```
OR with explicit eco type:
```
[sell]
<id>;<ecoType>;<ecoAmt>
```

- `<id>` - Do `/claimlist` to get this (not needed for short format)
- `<ecoType>` - Accepts `money`, `claimblocks`, `exp` or `item` (optional, defaults to `money`)
- `<ecoAmt>` - The sale price

> **Note:** Hold the item you wish to set in your offhand while creating an item-based sign.

### Mailbox Signs

**Condensed Format** (outside claim):
```
[mailbox]
<id>;<ecoAmt>
```
OR with explicit eco type:
```
[mailbox]
<id>;<ecoType>;<ecoAmt>
```

- `<id>` - Do `/claimlist` to get this
- `<ecoType>` - Accepts `money`, `claimblocks`, `exp` or `item` (optional, defaults to `money`)
- `<ecoAmt>` - The mailbox purchase price

> **Note:** You need to create a 1x1x1 3D subdivision in your claim with a supported container type (barrel, hopper, chest, etc.).

### Setup Wizards

Use the interactive setup wizards for easier sign creation:
- `/rentclaim` - Start rental sign setup wizard
- `/sellclaim` - Start sell sign setup wizard  
- `/mailbox` - Start mailbox setup wizard

The wizard will guide you through:
1. Claim selection (automatic if standing in your claim)
2. Payment type selection
3. Price/duration configuration
4. Optional auto-paste mode for sign placement

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

The plugin creates `config.yml` and `lang.yml` with sensible defaults. Key options include:

### config.yml
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

### lang.yml
All messages are customizable in `lang.yml`. The file includes sections for:
- General messages (prefix, permissions, etc.)
- Sign creation messages
- Wizard prompts and errors
- Claim command messages
- Admin messages

Use `/gpx reload` to reload both configuration files without restarting.

---

## Permissions

### Sign Creation
| Permission | Description |
|------------|-------------|
| `griefprevention.sign.create.rent` | Create rental signs |
| `griefprevention.sign.create.rent.anywhere` | Create rental signs outside the target claim |
| `griefprevention.sign.create.sell` | Create sell signs |
| `griefprevention.sign.create.sell.anywhere` | Create sell signs outside the target claim |
| `griefprevention.sign.create.mailbox` | Create mailbox signs |

### Sign Usage
| Permission | Description |
|------------|-------------|
| `griefprevention.sign.use.rent` | Use rental signs |
| `griefprevention.sign.use.sell` | Use sell signs |
| `griefprevention.sign.use.mailbox` | Use mailbox signs |

### Economy Types
| Permission | Description |
|------------|-------------|
| `griefprevention.sign.eco.money` | Create signs with money payments |
| `griefprevention.sign.eco.exp` | Create signs with experience payments |
| `griefprevention.sign.eco.claimblocks` | Create signs with claim block payments |
| `griefprevention.sign.eco.item` | Create signs with item payments |

### Sign Limits
| Permission | Description |
|------------|-------------|
| `griefprevention.sign.limit.rent.<number>` | Limit to <number> rental signs |
| `griefprevention.sign.limit.sell.<number>` | Limit to <number> sell signs |
| `griefprevention.sign.limit.mailbox.<number>` | Limit to <number> mailbox signs |

### Claim Management
| Permission | Description |
|------------|-------------|
| `griefprevention.claim.name` | Set claim names with `/claim name` |
| `griefprevention.claim.ban` | Ban players from claims with `/claim ban` |
| `griefprevention.claim.unban` | Unban players from claims with `/claim unban` |
| `griefprevention.claim.transfer` | Transfer claim ownership with `/claim transfer` |
| `griefprevention.claim.info` | View claim info with `/claim info` |

### Admin
| Permission | Description |
|------------|-------------|
| `griefprevention.admin` | Admin commands and sign management |
| `gpexpansion.admin.reload` | Use `/gpx reload` command |
| `gpexpansion.admin.max` | Use `/gpx max` command |
| `gpexpansion.admin.debug` | Use `/gpx debug` command |

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
