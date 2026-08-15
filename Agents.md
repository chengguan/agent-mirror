# Security Rules (Must Follow)

You must follow secure coding best practices at all times. Specifically:

## Core Principles
- Follow OWASP Mobile Top 10 and OWASP ASVS guidelines
- Prefer the principle of least privilege
- Never hardcode secrets, API keys, or sensitive data
- Always validate and sanitize all user input
- Use the most secure available APIs from Apple

## iOS-Specific Security Requirements
- Store sensitive data only in the Keychain (never UserDefaults or files for secrets)
- Use App Transport Security (ATS) — never disable it permanently
- Prefer biometric authentication (Face ID / Touch ID) + Keychain when possible
- Use CryptoKit for cryptographic operations (never implement your own crypto)
- Enable data protection (NSFileProtectionComplete) for sensitive files
- Avoid logging sensitive information
- Use URLSession with proper certificate pinning when communicating with backends (when relevant)
- Follow Apple’s App Tracking Transparency and privacy best practices

## When Writing Code
- Explicitly comment security-related decisions
- Prefer Swift’s type safety and modern concurrency
- Reject insecure patterns (e.g. storing tokens in plain text, weak encryption, disabled ATS, etc.)
- If a feature requires a security trade-off, stop and ask me first
