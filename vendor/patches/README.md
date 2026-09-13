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

Rebased onto upstream `45f4022` after the CCA-gate work landed. The Jaguar1
part remembers the independent primary/EDCCA gate state when re-deriving the
IGI-coupled EDCCA threshold; that integration now belongs to this patch rather
than the retired gate patch.

This directory was empty on purpose until now, and the reason it is not any
more is worth stating: the change had to be built and driven on real adapters
before it could honestly be proposed, and building it is what disproved the
hypothesis that motivated it. Carrying it here rather than in a fork keeps
`tools/host/vendor-devourer.sh` able to replay it onto a re-fetched pin, and
makes the divergence from `DEVOURER_VERSION` a file someone can read.

Retire it the moment it lands upstream.
