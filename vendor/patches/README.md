# vendor/patches

Local changes to the vendored Devourer live here as patch files, never as
unrecorded edits in `vendor/devourer/`. `tools/host/vendor-devourer.sh`
re-clones the pinned commit and replays every patch in lexical order, so an
upstream bump stays reviewable: if a patch stops applying, that is the signal
to look at what changed upstream.

Empty is the goal. A patch here is a debt — upstream it.

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
