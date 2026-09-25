# Pixel 5 / redfin device-support boundary

Qualification target: GD1YQ, stock Google Android 14 UP1A.231105.001.B2, Magisk 30.7, Verizon US voice SIM. Follow `docs/PIXEL5_REDFIN_SETUP.md` from the project root.

No audio-policy, mixer, SELinux, modem, IMS or HAL patch is shipped. v0.2.1 first exercises the existing telephony output and voice-downlink capture interfaces through the privileged generic Android agent.

If runtime evidence eventually justifies a minimal platform experiment, keep its exact stock-build prerequisite, original file hash, diff, scoped installation, rollback and before/after evidence here. Do not put model-specific mixer controls, root commands or firmware changes in the generic command executor. Investigate all preceding steps in `docs/AUDIO_TROUBLESHOOTING.md` before a policy experiment; consider HAL work only after those avenues are exhausted. Preserve stock Google/vendor components and SELinux enforcement.
