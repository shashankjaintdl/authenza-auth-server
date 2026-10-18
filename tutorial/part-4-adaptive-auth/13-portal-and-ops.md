# Chapter 13 — Portal & ops

| | |
|--|--|
| Part | [IV — Adaptive Auth](README.md) |
| Previous | [← Code walkthrough](12-code-walkthrough.md) |
| Next | [Part V →](../part-5-going-further/README.md) |

---

## Admin: Adaptive settings

**Terms:** `adaptive_auth_enabled`, thresholds, `adaptive_notify_new_device`, `adaptive_fail_mode`, trust days, IP lookback.

**Example API:**

```http
GET  /api/v1/settings/adaptive-auth
PUT  /api/v1/settings/adaptive-auth
Content-Type: application/json

{
  "adaptive_auth_enabled": "true",
  "adaptive_score_step_up": "40",
  "adaptive_score_block": "80",
  "adaptive_notify_new_device": "true",
  "adaptive_fail_mode": "OPEN"
}
```

Portal: Security → Adaptive authentication (toggle + fields as exposed).

**Ops note:** If GET returns 404, IAM service is likely not running or not rebuilt — not a “empty list” bug.

## User: Trusted devices

**Example:**

```http
GET    /api/v1/users/{userId}/trusted-devices
DELETE /api/v1/users/{userId}/trusted-devices/{deviceId}
```

Empty list with Adaptive off or never completed remember → **normal**.

## Security email

**Term:** `SecurityAlertEvent` on Redis channel `notification:security-alert`.

**Example condition:** Adaptive on, `notifyNewDevice=true`, new-device path → after full login, notification service renders `security-alert.html`.

Requires `auth-notification-service` + Redis.

## Lab

1. Enable Adaptive for a test tenant.  
2. Login from a “new” browser profile → expect MFA verify if enrolled, or allow + email if not.  
3. Complete login → trusted device row appears.  
4. Revoke device → login again → treat as new device.  
5. Confirm login page never shows score or signal names (enumeration resistance).

---

← [Ch 12](12-code-walkthrough.md) · [Part V — Going further →](../part-5-going-further/README.md)
