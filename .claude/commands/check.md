Run `./scripts/dev.sh check` (exchange-core unit tests + headless GameTests).

If anything fails: read the failure, find the root cause (for Minecraft API errors, confirm real names with
`./scripts/dev.sh api <class> [members]` before changing code), fix it, and rerun until green.
Finish with the test counts and a one-paragraph summary of what you changed. Do not commit unless asked.
