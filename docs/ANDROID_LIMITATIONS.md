# Android capability and qualification notes

These Phase A notes still govern ordinary sideloaded operation. Version 0.2's privileged audio implementation and its additional limits are described in [PHASE_B_AUDIO.md](PHASE_B_AUDIO.md).

These notes describe the public Android API boundary used by this PoC. They are not a claim of handset or carrier qualification. Primary Android sources were checked on 2026-09-25.

## Native SIM calling and the dialer role

The agent calls `TelecomManager.placeCall` using an explicitly selected native SIM phone account and receives detailed states through `InCallService`. It does not implement its own calling transport. Configure the default dialer role using Android's consent UI; installation and a phone permission alone do not establish that role.

A default dialer must handle `ACTION_DIAL` and provide incoming/ongoing call UI. The PoC includes engineering UI for these paths; confirm it remains usable with a locked screen and incoming calls on every candidate handset. Platform emergency handling remains under Android control; automated test numbers should be controlled non-emergency destinations.

Source: [Android InCallService: becoming the default phone app](https://developer.android.com/reference/android/telecom/InCallService#becoming-the-default-phone-app).

## What a call measurement means

`Call.STATE_ACTIVE` is the handset's Telecom connection observation. It is useful for setup timing, but does not establish first audible media, remote application readiness, negotiated codec, packet loss or MOS. It is not a SIP 200 timestamp obtained from the carrier. Disconnect causes may be generic rather than detailed network signaling failures.

Source: [Android Call states and callbacks](https://developer.android.com/reference/android/telecom/Call).

## LTE does not establish VoLTE

Report `voice_network_type` and `data_network_type` separately. An LTE/5G data connection, an enabled advanced-calling setting, or a native SIM account does not independently verify the call bearer. A carrier SIM call may use Wi-Fi Calling or cross-SIM/backup calling when enabled; NR data also does not prove VoNR. Disable those alternatives for cellular-bearer acceptance and preserve carrier/OEM diagnostic evidence if definitive VoLTE verification is required.

IMS registration APIs can require privileged/precise phone-state access or carrier privileges. A normal sideloaded app must tolerate access denial and report IMS as unknown/unavailable. Do not report `IMS_NOT_REGISTERED` merely because permission was denied, and do not reject a call solely because IMS is unreadable. Even IMS registration alone does not prove which bearer a particular call used.

Sources: [TelephonyManager voice and data network information](https://developer.android.com/reference/android/telephony/TelephonyManager), [ImsMmTelManager registration callbacks and requirements](https://developer.android.com/reference/android/telephony/ims/ImsMmTelManager).

## Radio measurements are best effort

Precise location permission and enabled Location are needed for useful cell information. Background operation may additionally need background location. The foreground agent service is not a shortcut around location permission. Grant background location separately in settings and test again with the UI closed.

`getAllCellInfo()` returns cached information for apps targeting Android 10+. Requested updates are rate limited and not guaranteed. Record sample age and unavailable reasons; never relabel cached measurements as fresh. Neighbor/registered-cell flags are not a full modem trace. OEMs and carriers can expose different subsets of LTE/NR bands, channels, PCI, cell IDs, RSRP/RSRQ/RSSI/SINR, and signal levels. Missing values are normal. Do not map a single EARFCN to a claimed active band without acknowledging overlapping allocations; use exposed band information where available.

Cell information can include all device radios. This PoC labels that scope explicitly instead of inferring a subscription for each cell. Keep the first acceptance run single-SIM. Future dual-SIM tests need separate validation of account selection and measurement attribution; on API 29, ambiguous phone-account-to-subscription mapping is rejected.

Sources: [TelephonyManager cell-info caching and permissions](https://developer.android.com/reference/android/telephony/TelephonyManager#getAllCellInfo()), [background location access](https://developer.android.com/develop/sensors-and-location/location/permissions/background), [CellIdentityLte](https://developer.android.com/reference/android/telephony/CellIdentityLte), [CellSignalStrengthLte](https://developer.android.com/reference/android/telephony/CellSignalStrengthLte).

## A foreground service is not an availability guarantee

The persistent diagnostics/polling agent uses the `specialUse` foreground-service type with an explanatory manifest subtype. Call observation is provided by Telecom's bound `InCallService`. This choice does not exempt the app from general foreground-start rules, OS power policy or OEM process management. A Play Store release would require a separate review of the declared foreground-service use case.

Android 12+ restricts background starts. Android 15 also restricts launching several foreground-service types, including `phoneCall`, from `BOOT_COMPLETED`. The app must handle a refused start and expose a recovery action. Doze can defer network/CPU work. A configured poll interval therefore describes intent, not a maximum command latency.

`START_STICKY`, boot/package-replaced handling, connectivity callbacks and retry backoff support recovery; they cannot promise it on an unqualified handset. Keep the appliance powered, review battery/OEM settings, and measure reconnect latency after idle, reboot and network outages.

Sources: [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [Android 15 foreground-service changes](https://developer.android.com/about/versions/15/changes/foreground-service-types), [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby).

## Boot, first unlock and force-stop

This PoC is not a Direct Boot application. Its normal credential-encrypted app storage is available after the first unlock. Test the reboot path after that unlock and record any manual interaction still required. The app must be launched at least once following install and enrollment.

A user force-stop is different from process eviction. Android keeps the package stopped until user action reactivates it; the app must not claim automatic recovery from force-stop. `REBOOT_APP` only restarts the agent loop. Neither it nor `BOOT_COMPLETED` supplies a general device-reboot API.

Sources: [Direct Boot and storage availability](https://developer.android.com/privacy-and-security/direct-boot), [Android 15 stopped-state behavior](https://developer.android.com/about/versions/15/behavior-changes-all#stopped-state).

## HTTPS and certificates

Use a hostname-matching certificate with a chain trusted by Android. The reference client keeps normal certificate and hostname verification and rejects cleartext control. Apps targeting modern Android do not automatically trust user-installed CAs. If an internal test CA is required, scope it deliberately through network-security configuration and rebuild; do not add a trust-all verifier.

Source: [Android network security configuration](https://developer.android.com/privacy-and-security/security-config).

## Privileged cellular audio work

Phase A downloads and verifies a WAV. Version 0.2 adds privileged telephony-route AudioTrack and voice-source AudioRecord implementations; ordinary playback alone does not establish a direct digital uplink path, and default-dialer status does not grant unrestricted call-audio access. Keep physical audio qualification separate from control-plane acceptance. Vendor/HAL changes require device evidence and are not included by default.

Source: [Android audio input sharing and voice-call capture restrictions](https://developer.android.com/media/platform/sharing-audio-input).
