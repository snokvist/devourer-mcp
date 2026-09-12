# vendor/patches

Local changes to the vendored Devourer live here as patch files, never as
unrecorded edits in `vendor/devourer/`. `tools/host/vendor-devourer.sh`
re-clones the pinned commit and replays every patch in lexical order, so an
upstream bump stays reviewable: if a patch stops applying, that is the signal
to look at what changed upstream.

Empty is the goal. A patch here is a debt — upstream it.
