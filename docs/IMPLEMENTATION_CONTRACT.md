# Implementation contract

The current control protocol is documented in [API.md](API.md); experimental digital TX/RX operations and artifact transfer are documented in [PHASE_B_AUDIO.md](PHASE_B_AUDIO.md). The original Phase A enrollment, native-call, telemetry and persistent-operation interfaces are retained in v0.2.1.

Test scenarios, sequencing, call destinations and retries remain orchestrator responsibilities. Command submission success is distinct from asynchronous call/audio outcomes. Hardware acceptance follows [ACCEPTANCE_TEST.md](ACCEPTANCE_TEST.md) and [AUDIO_ACCEPTANCE_TEST.md](AUDIO_ACCEPTANCE_TEST.md).
