# Upstream proposal: address the two CCA gates separately, and stop enabling EDCCA by default on Jaguar1

For OpenIPC/devourer. Built and measured on this bench first; the patch is
`vendor/patches/0002-cca-gates.patch`.

**Item 1 (the gate split) is upstream as
[OpenIPC/devourer#427](https://github.com/OpenIPC/devourer/pull/427)**,
implemented and hardware-verified on Jaguar1 and Jaguar3. Items 2-4 — the
EDCCA-on-by-default policy, the hard-coded thresholds, and scoping the
`CLAUDE.md` claim — are raised in that PR's description but deliberately not
in its diff: they are policy calls for the maintainer, not bugs.

## The measurement

An RTL8812AU injecting 300 broadcast frames at 6M on ch6, carrier sense in
each of its four possible states, **a fresh radio open per arm** so nothing
latches across points, two independent receivers, arms interleaved and
repeated:

| Gate state | delivered |
|---|---|
| both on (devourer's default) | 0.0%, 1.7% |
| **EDCCA off only** | **94.3%, 94.7%** |
| primary CCA off only | 13.7%, 2.7% |
| both off | 94.3%, 95.3% |

**On Jaguar1, EDCCA is the gate that stops injection.** Turning it off alone
recovers everything; turning off primary CCA alone recovers almost nothing.

## Why that is worth reporting

`CLAUDE.md` currently states the opposite as a general fact:

> The primary-CCA bit is the one that matters: monitor injection is not
> CCA-free, it defers ~40-60% to a co-channel 802.11 transmitter, and
> clearing `[14]` recovers ~1.5-2.2x (on-air 8822EU/8812CU,
> `tests/dis_cca_tx_onair.sh`); the energy bit `[15]` alone is null against a
> decodable preamble.

That is measured, and it is Jaguar3. `tests/dis_cca_tx_onair.sh` uses the
8812AU **only as the flooder** — Jaguar1 has never been the DUT. On Jaguar1
the result inverts: the energy bit is everything and the preamble bit is
nearly null. The claim is family-specific and currently reads as general.

## Why Jaguar1 is different: devourer turns EDCCA on, and the vendor does not

`SetCcaMode(false)` on this family is not a no-op. The BB init table parks the
EDCCA thresholds at `0x8a4 = 0x7f7f`, never-trigger — and devourer's own
comment names that as *"the vendor's adaptivity-off default
(CONFIG_RTW_ADAPTIVITY_EN 0)"*. Bring-up then programs the vendor adaptivity
operating point off the live IGI, which is what brings EDCCA into existence.

The Realtek vendor driver ships that feature **off**, and when it is on it
makes both thresholds runtime-tunable. From `rtl88x2eu-5.15.0.1` on this
machine:

```c
#define CONFIG_RTW_ADAPTIVITY_EN 0               /* include/drv_conf.h */
#define CONFIG_RTW_ADAPTIVITY_TH_L2H_INI 0
#define CONFIG_RTW_ADAPTIVITY_TH_EDCCA_HL_DIFF 0

module_param(rtw_adaptivity_en, uint, 0644);     /* "0:disable, 1:enable, 2:auto" */
module_param(rtw_adaptivity_mode, uint, 0644);   /* "0:normal, 1:carrier sense" */
module_param(rtw_adaptivity_th_l2h_ini, int, 0644);
module_param(rtw_adaptivity_th_edcca_hl_diff, int, 0644);
```

devourer hard-codes both: `th_l2h_ini` is `-17` (`-14` on the 8814A) and the
H2L gap is `7`, in `jaguar1_edcca_l2h`. Adaptivity is an ETSI regulatory
feature; enabling it unconditionally, with fixed thresholds, is a policy
choice the vendor did not make.

## You do not have to disable carrier sense to fix this

The interesting half. With EDCCA off and **primary CCA still on**, against a
saturating co-channel flooder:

| Arm | flooder | delivered |
|---|---|---|
| EDCCA off, primary CCA ON | no | 95.3% |
| EDCCA off, primary CCA ON | yes | 78.0% |
| both gates off | yes | **0.3%** |

Carrier sense is still working — delivery drops 95% to 78% when a real
transmitter takes the channel, which is what deferral is supposed to look
like. And turning *both* gates off is not just antisocial, it is **worse for
your own delivery on a busy channel**: the injector transmits straight into
the flood and collides, 0.3% against 78%.

So the fix is not `dis_cca`. It is EDCCA off, primary CCA on — which is the
configuration the vendor driver ships.

## The proposal

1. **`IRtlRadio::SetCcaGates(bool primary_disabled, bool edcca_disabled)`**
   and `GetCcaGates`, with not-ported defaults. `SetCcaMode` stays as the
   portable all-or-nothing call and becomes `SetCcaGates(d, d)`. Implemented
   and verified on jaguar1 here; the split is a Realtek 0x520 property so the
   other Realtek families are mechanical, and MediaTek correctly reports
   unsupported.
2. **Do not enable EDCCA on Jaguar1 by default.** Match
   `CONFIG_RTW_ADAPTIVITY_EN 0`: leave `0x8a4` parked unless asked. Measured
   cost of the current default on an 8812AU injector: 94% of frames.
3. **If it is enabled, make the thresholds config rather than constants** —
   `DeviceConfig.rx.adaptivity_th_l2h_ini` and `..._th_edcca_hl_diff`,
   mirroring the vendor's module parameters. `jaguar1_edcca_l2h`'s `-17`/`7`
   become their defaults.
4. **Correct the `CLAUDE.md` claim** to say which family it was measured on,
   and record the Jaguar1 inversion beside it.

Only jaguar1 and the MediaTek not-ported path were exercised here. Jaguar2,
Jaguar3, Kestrel and RTL8733B are left to the maintainer.

## Test plan

`tests/dis_cca_tx_onair.sh` already has the shape; it needs a Jaguar1 DUT arm
and the two gates as separate cells rather than one `dis_cca` flag. The
no-flooder baseline is the important addition — on Jaguar3 the DUT transmits
at full rate alone, and on Jaguar1 it does not, which is the whole finding.
