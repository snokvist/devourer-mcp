# vendor/patches

Local changes to the vendored Devourer live here as patch files, never as
unrecorded edits in `vendor/devourer/`. `tools/host/vendor-devourer.sh`
re-clones the pinned commit and replays every patch in lexical order, so an
upstream bump stays reviewable: if a patch stops applying, that is the signal
to look at what changed upstream.

Empty is the goal. A patch here is a debt — upstream it.

**Paths must be devourer-relative** (`a/src/...`, not
`a/vendor/devourer/src/...`). `tools/host/vendor-devourer.sh` replays with
`git apply --directory=devourer`, which prepends the prefix itself; a
repo-relative patch doubles it and the sync aborts. Generate with
`git diff --relative=vendor/devourer <from> <to> -- vendor/devourer`.

## 0001-rx-gain-range.patch

A vendor-neutral receive-gain contract — `GetRxGainCaps` / `GetRxGainState` /
`SetRxGainRange` on `IRadio`, implemented on jaguar1 and jaguar3. Proposed
upstream; see [`docs/proposals/rx-gain-range.md`](../../docs/proposals/rx-gain-range.md)
for the design and the measurements.

This directory was empty on purpose until now, and the reason it is not any
more is worth stating: the change had to be built and driven on real adapters
before it could honestly be proposed, and building it is what disproved the
hypothesis that motivated it. Carrying it here rather than in a fork keeps
`tools/host/vendor-devourer.sh` able to replay it onto a re-fetched pin, and
makes the divergence from `DEVOURER_VERSION` a file someone can read.

Retire it the moment it lands upstream.

## 0002-cca-gates.patch

`IRtlRadio::SetCcaGates` / `GetCcaGates` — the MAC carrier-sense gate one bit
at a time, because on Jaguar1 the two bits do opposite things and
`SetCcaMode` can only move them together. See
[`docs/proposals/cca-gates-and-adaptivity.md`](../../docs/proposals/cca-gates-and-adaptivity.md);
the measurement it enabled is that EDCCA alone costs an 8812AU injector 94%
of its frames, which inverts the Jaguar3 result devourer documents as general.

Upstream as [OpenIPC/devourer#427](https://github.com/OpenIPC/devourer/pull/427),
derived against a pristine tree. **This file is a superset of that PR**, not
the same diff with different context:

- it is stacked on 0001, so the shared files carry that patch's hunks too; and
- it additionally gives Jaguar1 the `_cca_primary_disabled` /
  `_cca_edcca_disabled` members, because 0001's `SetRxGainRange` re-applies
  carrier sense after moving IGI (the EDCCA threshold is derived from it) and
  must re-apply *the gates the caller actually set* rather than `SetCcaMode`,
  which would switch a deliberately-disabled gate back on. Upstream has no
  rx-gain contract yet, so it has no such call site and does not need the
  state.

When 0001 lands, that second point folds back into it. Retire this file when
the PR lands, and check that the Jaguar1 members went somewhere.

It now also carries the review round on that PR, all of it verified here
before being answered:

- `0x524[11]` is `BIT_EDCCA_MSK_CNTDOWN_EN` (`REG_RD_CTRL`), named identically
  on 8822B/8822C/8822E in the vendor HALMAC headers, so it follows the EDCCA
  gate alone rather than the pair. Keyed on the pair — as first written — the
  EDCCA-off arm this work recommends left EDCCA still masking the backoff
  countdown. `SetCcaMode`'s two states are byte-identical either way.
- phydm's `edcca_track` is keyed on the EDCCA gate, not on the all-or-nothing
  flag, so the ~2 s watchdog stops rewriting the BB thresholds when EDCCA is
  the gate that was turned off.
- Both gate calls refuse before bring-up on Jaguar3 as they already did on
  Jaguar1: reading `0x520` on an unconfigured MAC is a fabricated measurement
  and the old code returned it as fact.
- `tests/cca_gates_probe.cpp` + `tests/cca_gates_regcheck.sh` are the in-tree
  caller and the register-level check, so the tables live in the repo instead
  of only in a PR description.

**Regenerate this file last.** It is a diff of the vendored tree, so editing
`vendor/devourer/` after generating it leaves a patch that silently reverts
those edits on the next `tools/host/vendor-devourer.sh`.

