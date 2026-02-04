<p align="center">
<img alt="GPExpansion" width=100% height=auto src="https://repository-images.githubusercontent.com/1126629614/1abbf9aa-6973-4059-a255-2baefd6fe766">
</p>

<h1 align="center">Extend GriefPrevention with rental signs, mailboxes, and more</h1>

<p align="center">
<a href="https://github.com/castledking/GPExpansion/releases"><img alt="Downloads" src="https://img.shields.io/badge/Downloads-green" height="70px"></a>
<a href="https://github.com/castledking/GPExpansion/wiki"><img alt="Docs" src="https://img.shields.io/badge/Docs-gray?logo=readthedocs&logoColor=white" height="70px"></a>
<a href="#support"><img alt="Get Help" src="https://img.shields.io/badge/Get%20Help-yellow?logo=amazoncloudwatch&logoColor=white" height="70px"></a>
</p>

GPExpansion adds powerful features to [GriefPrevention](https://github.com/GriefPrevention/GriefPrevention) including **rental signs**, **claim mailboxes**, **global claims** **sign protection**, and more — all while maintaining the self-service philosophy.

Mailboxes work with **regular GriefPrevention** or the [GriefPrevention3D](https://github.com/castledking/GriefPrevention3D) fork. With regular GP you can use the **virtual** mailbox protocol (no subdivisions). With GP3D you can use **real** (1x1x1 subdivision + container trust) or **virtual**; the config option `mailbox-protocol` defaults based on which GP you have installed.

---

## Supported Platforms
**Spigot, Paper, Purpur, and Folia**

Requires [GriefPrevention](https://github.com/GriefPrevention/GriefPrevention) and optionally [Vault](https://github.com/MilkBowl/Vault) for economy features.

Optionally, use [GriefPrevention3D](https://github.com/castledking/GriefPrevention3D) for seamless mailboxes (1x1x1 subdivisions and public container trust). Mailboxes will work with regular GP and there is a config setting called mailbox-protocol: virtual that helps if you want to be able to stack mailboxes on top of eachother.

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
- Non-owners see a virtual inventory (snapshot at open time)
  - Changes save only when the menu closes
  - Can take back items before closing (reversible deposits)
  - Items returned if mailbox fills up while viewing
- Chest opening sound/animation on sign click
- Storage warnings when mailbox is nearly full
- Purchasable via signs with configurable prices
- Interactive setup wizard with `/mailbox` command

**Self Mailboxes (Instant Creation):**
- Owners and renters can place `[Mailbox]` wall signs directly on containers in claims they own or rent
- **Real protocol** (config `mailbox-protocol: real`): creates a 1x1x1 subdivision (GP3D) or 1x1 2D subdivision (regular GP) and grants public container trust so non-owners see the real chest with live updates
- **Virtual protocol** (config `mailbox-protocol: virtual`): no subdivision; non-owners get a virtual snapshot, no live updates. Allows for stackable mailboxes on regular GP.
- Configurable limit via `defaults.max-self-mailboxes-per-claim`

### 🏷️ Sell Signs
Allow claim owners to sell their claims to other players using signs.
- Set claim prices
- Automatic transfer of ownership of claim
- Supports Vault economy, experience, claim blocks, and item-based payments
- Interactive setup wizard with `/sellclaim` command

### 🌍 Global Claims
Allow claims to be listed in a global claim list, viewable to all.
- Set global settings like icon, description, name, spawn point, and more.
- Allows users to teleport to global claims via GUI by default.
- Allows [global] claim signs to instantly set spawn and list as global.
- Simple /claim global <true|false> command to manage global listing status.

### 🔒 Sign Protection
Protect your rental and mailbox signs from unauthorized modification.
- Admin-only sign breaking for active rentals
- Automatic cleanup on sign removal

### 📋 Claim Management
- `/claim name <name>` - Set claim name (supports color codes with permissions)
- `/claim desc <description>` - Set claim description (uses same color permissions as name)
- `/claim icon <material>` - Set claim icon for GUI display
- `/claim spawn` - Set the teleport spawn point for your claim
- `/claim tp [claimId]` - Teleport to a claim's spawn point
- `/claim ban <player>` - Ban players from your claims
- `/claim unban <player>` - Unban players from your claims
- `/claim info [claimId]` - View detailed claim information
- `/mailbox` - Manage your mailboxes
- `/gpx reload` - Reload configuration and language files
- `/gpx max` - Manage player sign creation limits

### 💰 Configurable Tax System
Optional tax system for claim maintenance:
- Configurable tax rates per claim block
- Multiple payment methods (money, claim blocks, experience)
- Grace periods before claim deletion
- Tax exemptions via permissions

---

## Sign Formats

All sign types support flexible formatting where `<ecoType>` is optional and defaults to `money` if only a number is provided.

### Rental Signs

**Short Format** (place sign inside claim):

*Format A* - Space separated:
```
[rent]
<renewTime>
<ecoAmt>
```
OR with max time:
```
[rent]
<renewTime> <maxTime>
<ecoAmt>
```
OR with explicit eco type:
```
[rent]
<renewTime> <maxTime>
<ecoType>
<ecoAmt>
```

*Format B* - Semicolon delimited (NEW!):
```
[rent]
<renewTime>;<ecoAmt>
```
OR with max time:
```
[rent]
<renewTime>;<ecoAmt>;<maxTime>
```
OR with amount first:
```
[rent]
<ecoAmt>;<renewTime>
```
OR with max time:
```
[rent]
<ecoAmt>;<renewTime>;<maxTime>
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
OR with explicit eco type:
```
[rent]
<id>;<ecoType>;<ecoAmt>;<renewTime>
```
OR with max time:
```
[rent]
<id>;<ecoType>;<ecoAmt>;<renewTime>;<maxTime>
```

- `<id>` - Do `/claimlist` to get this (not needed for short format)
- `<ecoType>` - Accepts `money`, `claimblocks`, `exp` or `item` (optional, defaults to `money`)
  - Also accepts aliases: `$` (money), `xp`/`experience` (exp), `blocks`/`cb` (claimblocks)
- `<ecoAmt>` - The cost per renewal period
- `<renewTime>` - Duration of each rental period (e.g., `1w`, `7d`, `24h`, `30m`, `45s`)
- `<maxTime>` - Maximum total rental duration (optional, defaults to `<renewTime>`)

> **Note:** Hold the item you wish to set in your offhand while creating an item-based sign.

### Sell/Buy Signs

**Short Format** (place sign inside claim):
```
[sell]
<ecoAmt>
```
OR with explicit eco type:
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
  - Also accepts aliases: `$` (money), `xp`/`experience` (exp), `blocks`/`cb` (claimblocks)
- `<ecoAmt>` - The sale price

> **Note:** Hold the item you wish to set in your offhand while creating an item-based sign.

### Mailbox Signs

All mailbox signs are placed as **wall signs** attached to a container (chest, barrel, hopper, shulker box, etc.) inside a claim. No claim ID is required on the sign.

**Buyable Mailbox** (instant creation — claim owner only):
```
[Mailbox]
money;100
```
- Line 0: `[Mailbox]`
- Line 1: `kind;amount` — e.g. `money;100`, `xp;50`, `experience;50`, `claimblocks;10`
- Kind aliases: `money` or `$`, `xp` / `experience` / `exp`, `claimblocks` / `blocks` / `cb`
Place the sign on a container in a claim you own. Others can click the sign or the container to purchase the mailbox (subdivision or virtual slot is created on purchase).

**Self Mailbox** (instant creation for owners/renters):
```
[Mailbox]
ze_flash
anotherPlayer
```
- Line 0: `[Mailbox]`
- Lines 1–3: empty, or player names for **shared full access** (multi-way mailbox). Only the mailbox owner and players listed on the sign get full access; others get deposit-only (real) or virtual view (virtual).
Place the sign on a container in a claim you own or rent. Behavior depends on `mailbox-protocol`: **real** creates a subdivision and public container trust; **virtual** creates no subdivision (works with regular GP).

> **Note:** The container must be inside a claim. With **real** protocol a 1x1 (2D) or 1x1x1 (GP3D) subdivision is created around the container on creation or purchase; with **virtual** protocol no subdivision is created.

### Global Signs
**Player** (inside claim):
```
[global]
```

**Admin** (outside claim):
```
[global]
<id>
```

- `<id>` - Do `/claimlist` to get this (for global admin sign)

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

# Mailbox protocol (first-time default is set from GP: GP3D -> real, regular GP -> virtual)
# real = create subdivision + container trust public (non-owner opens real chest with live updates)
# virtual = no subdivision, virtual view for non-owners (works with regular GP, no extra subdivisions)
mailbox-protocol: virtual

# Default limits for sign creation (can be overridden by permissions)
defaults:
  # Default maximum number of sell signs a player can create
  max-sell-signs: 5
  # Default maximum number of rent signs a player can create
  max-rent-signs: 5
  # Default maximum number of mailbox signs a player can create
  max-mailbox-signs: 5
  # Max self mailboxes per claim (for claims owned or rented by the player)
  max-self-mailboxes-per-claim: 1

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

## GUI Configuration

All GUIs are fully configurable via YAML files in `plugins/GPExpansion/gui/`. Each GUI supports:
- Custom titles, sizes, and layouts
- PlaceholderAPI placeholders for dynamic content
- Custom items with materials, names, lore, and custom model data
- Textured player heads (base64 textures or player names)

### Items from Custom Resource Pack

Use PlaceholderAPI placeholders in item names/GUI title/lore for **ItemsAdder**. **Oraxen**, and **Nexo** using <glyph:my_image>.

**ItemsAdder Example:**
```yaml
items:
  my-item:
    material: PAPER
    name: "%img_custom_icon% &aMy Custom Item"
    lore:
      - "%img_arrow% &7Click to continue"
    custom-model-data: 10001
```

**Oraxen Example:**
```yaml
items:
  my-item:
    material: PAPER
    name: "<glyph:my_icon> &bOraxen Item"
    lore:
      - "<glyph:bullet> &7Some description"
    custom-model-data: 20001
```

**Nexo Example:**
```yaml
items:
  my-item:
    material: PAPER
    name: "<glyph:my_image> &dNexo Item"
    custom-model-data: 30001
```

### Textured Player Heads

Use custom skull textures from sites like [minecraft-heads.com](https://minecraft-heads.com):

**Base64 Texture (Custom Head):**
```yaml
items:
  settings-icon:
    material: PLAYER_HEAD
    skull-texture: "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjc..."
    name: "&e⚙ Settings"
    lore:
      - "&7Click to open settings"
```

**Direct Texture URL:**
```yaml
items:
  custom-icon:
    material: PLAYER_HEAD
    skull-texture: "http://textures.minecraft.net/texture/b7d153..."
    name: "&aCustom Icon"
```

**Player Head (Dynamic):**
```yaml
items:
  player-info:
    material: PLAYER_HEAD
    skull-owner: "{player}"  # Current player's head
    name: "&a{player_name}'s Profile"
```

**Specific Player Head:**
```yaml
items:
  owner-head:
    material: PLAYER_HEAD
    skull-owner: "Notch"  # Or UUID: "069a79f4-44e9-4726-a5be-fca90e38aaf5"
    name: "&6Owner: Notch"
```

### Example GUI Config (claim-menu.yml)

```yaml
title: "&8&l✦ &6Claim Menu &8&l✦"
size: 54

items:
  info-button:
    slot: 13
    material: PLAYER_HEAD
    skull-owner: "{player}"
    name: "&a&l{claim_name}"
    lore:
      - ""
      - "&7Owner: &f{owner}"
      - "&7Size: &f{area} blocks"
      - ""
      - "%img_left_click% &eLeft-click for details"
  
  settings-button:
    slot: 31
    material: PLAYER_HEAD
    skull-texture: "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6..."
    name: "&e⚙ Claim Settings"
    lore:
      - "&7Manage your claim"

filler:
  material: GRAY_STAINED_GLASS_PANE
```

---

## Permissions

### Sign Creation
| Permission | Description |
|------------|-------------|
| `griefprevention.sign.create.rent` | Create rental signs |
| `griefprevention.sign.create.rent.anywhere` | Create rental signs outside the target claim |
| `griefprevention.sign.create.sell` | Create sell signs |
| `griefprevention.sign.create.sell.anywhere` | Create sell signs outside the target claim |
| `griefprevention.sign.create.buy` | Create buy signs (alias for sell) |
| `griefprevention.sign.create.buy.anywhere` | Create buy signs outside the target claim |
| `griefprevention.sign.create.mailbox` | Create mailbox signs |
| `griefprevention.sign.create.self-mailbox` | Create self mailbox signs |

### Sign Usage
| Permission | Description |
|------------|-------------|
| `griefprevention.sign.use.rent` | Use rental signs |
| `griefprevention.sign.use.buy` | Use sell signs |
| `griefprevention.sign.use.mailbox` | Use mailbox signs |

### Economy Types (per sign type)
| Permission | Description |
|------------|-------------|
| `griefprevention.sign.rent.money` | Use money economy for rent signs |
| `griefprevention.sign.rent.exp` | Use experience economy for rent signs |
| `griefprevention.sign.rent.claimblocks` | Use claim blocks economy for rent signs |
| `griefprevention.sign.rent.item` | Use item economy for rent signs |
| `griefprevention.sign.buy.money` | Use money economy for sell signs |
| `griefprevention.sign.buy.exp` | Use experience economy for sell signs |
| `griefprevention.sign.buy.claimblocks` | Use claim blocks economy for sell signs |
| `griefprevention.sign.buy.item` | Use item economy for sell signs |
| `griefprevention.sign.mailbox.money` | Use money economy for mailbox signs |
| `griefprevention.sign.mailbox.exp` | Use experience economy for mailbox signs |
| `griefprevention.sign.mailbox.claimblocks` | Use claim blocks economy for mailbox signs |
| `griefprevention.sign.mailbox.item` | Use item economy for mailbox signs |

### Sign Limits
| Permission | Description |
|------------|-------------|
| `griefprevention.sign.limit.rent.<number>` | Limit to <number> rental signs |
| `griefprevention.sign.limit.buy.<number>` | Limit to <number> sell signs |
| `griefprevention.sign.limit.mailbox.<number>` | Limit to <number> mailbox signs |
| `griefprevention.sign.create.self-mailbox.<number>` | Limit to <number> self mailboxes per claim |

### Claim Management
| Permission | Description |
|------------|-------------|
| `griefprevention.claim.name` | Set claim names with `/claim name` |
| `griefprevention.claim.name.anywhere` | Use `/claim name` outside own claims |
| `griefprevention.claim.name.other` | Use `/claim name` on other claims |
| `griefprevention.claim.description` | Set claim descriptions with `/claim desc` |
| `griefprevention.claim.description.anywhere` | Use `/claim desc` outside own claims |
| `griefprevention.claim.description.other` | Use `/claim desc` on other claims |
| `griefprevention.claim.icon` | Set claim icons with `/claim icon` |
| `griefprevention.claim.icon.other` | Use `/claim icon` on other claims |
| `griefprevention.claim.setspawn` | Set claim spawn point with `/claim spawn` |
| `griefprevention.claim.setspawn.other` | Set spawn point on other claims |
| `griefprevention.claim.teleport` | Teleport to claims with `/claim tp` |
| `griefprevention.claim.teleport.other` | Teleport other players to claims |
| `griefprevention.claim.ban` | Ban players from claims with `/claim ban` |
| `griefprevention.claim.ban.anywhere` | Ban outside own claims |
| `griefprevention.claim.ban.other` | Ban players from other claims |
| `griefprevention.claim.unban` | Unban players from claims with `/claim unban` |
| `griefprevention.claim.unban.anywhere` | Unban outside own claims |
| `griefprevention.claim.unban.other` | Unban players from other claims |
| `griefprevention.transferclaim` | Transfer claim ownership with `/claim transfer` |
| `griefprevention.claiminfo` | View claim info with `/claim info` |
| `griefprevention.claiminfo.other` | View claim info for others' claims |
| `griefprevention.claim.toggleglobal` | Toggle global listing for own claims |
| `griefprevention.claim.toggleglobal.other` | Toggle global listing for other claims |

### Color & Formatting Permissions (Name & Description)

These permissions control which color and formatting codes players can use in both `/claim name` and `/claim desc`:

| Permission | Description | Codes |
|------------|-------------|-------|
| `griefprevention.claim.color.black` | Use black color | `&0` |
| `griefprevention.claim.color.dark_blue` | Use dark blue color | `&1` |
| `griefprevention.claim.color.dark_green` | Use dark green color | `&2` |
| `griefprevention.claim.color.dark_aqua` | Use dark aqua color | `&3` |
| `griefprevention.claim.color.dark_red` | Use dark red color | `&4` |
| `griefprevention.claim.color.dark_purple` | Use dark purple color | `&5` |
| `griefprevention.claim.color.gold` | Use gold color | `&6` |
| `griefprevention.claim.color.gray` | Use gray color | `&7` |
| `griefprevention.claim.color.dark_gray` | Use dark gray color | `&8` |
| `griefprevention.claim.color.blue` | Use blue color | `&9` |
| `griefprevention.claim.color.green` | Use green color | `&a` |
| `griefprevention.claim.color.aqua` | Use aqua color | `&b` |
| `griefprevention.claim.color.red` | Use red color | `&c` |
| `griefprevention.claim.color.light_purple` | Use light purple/pink color | `&d` |
| `griefprevention.claim.color.yellow` | Use yellow color | `&e` |
| `griefprevention.claim.color.white` | Use white color | `&f` |
| `griefprevention.claim.format.obfuscated` | Use obfuscated/magic text | `&k` |
| `griefprevention.claim.format.bold` | Use bold text | `&l` |
| `griefprevention.claim.format.strikethrough` | Use strikethrough text | `&m` |
| `griefprevention.claim.format.underline` | Use underlined text | `&n` |
| `griefprevention.claim.format.italic` | Use italic text | `&o` |
| `griefprevention.claim.format.reset` | Use reset formatting | `&r` |
| `griefprevention.claim.color.*` | All color codes | `&0-&f` |
| `griefprevention.claim.format.*` | All format codes | `&k-&r` |

> **Note:** The same permissions apply to both `/claim name` and `/claim desc`. If a player uses a color code they don't have permission for, that code will be stripped from the text.

### Admin
| Permission | Description |
|------------|-------------|
| `griefprevention.admin` | Admin commands and sign management |
| `gpx.admin` | View migration notices |
| `griefprevention.adminclaimslist` | View admin claims list |
| `griefprevention.sign.admin` | Admin sign management |
| `gpexpansion.admin.reload` | Use `/gpx reload` command |
| `gpexpansion.admin.max` | Use `/gpx max` command |
| `gpexpansion.admin.debug` | Use `/gpx debug` command |

### GUI & GPFlags
| Permission | Description |
|------------|-------------|
| `griefprevention.claim.gui.globallist` | Open global claim list GUI |
| `griefprevention.claim.gui.return` | Use `/claim !` to return to last GUI |
| `griefprevention.claim.gui.setclaimflag.own` | Open claim flags GUI for own claims |
| `griefprevention.claim.gui.setclaimflag.anywhere` | Open claim flags GUI for other claims |
| `gpflags.command.setclaimflag` | Use GPFlags claim flag commands |
| `gpflags.flag.*` | Toggle any GPFlags flag |

### Moderation
| Permission | Description |
|------------|-------------|
| `griefprevention.evict` | Evict renters |
| `griefprevention.evict.other` | Evict renters from other claims |
| `griefprevention.eviction.bypass` | Bypass eviction protections |

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
