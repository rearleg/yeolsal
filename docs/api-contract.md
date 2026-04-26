# API Contract

Base path: `/api/v1`

## Auth

- `POST /auth/signup`
- `POST /auth/login`
- `POST /auth/kakao`
- `POST /auth/refresh`
- `POST /auth/logout`

## Friends and Feed

- `GET /friends`
- `POST /friends/requests`
- `PATCH /friends/requests/{id}`
- `GET /feed/daily?date=YYYY-MM-DD`

## Daily Work

- `POST /daily-entries`
- `POST /reflections`
- `GET /monthly-goals?month=YYYY-MM`
- `POST /monthly-goals`
- `GET /stats/monthly?month=YYYY-MM`

## Profiles

- `GET /profiles/me`
- `GET /profiles/{userId}`
- `GET /profiles/{userId}/grass?from=YYYY-MM-DD&to=YYYY-MM-DD`

## Grass Day

```json
{
  "date": "2026-04-26",
  "missionCompleted": true,
  "completedTodoCount": 3,
  "reflectionSubmitted": true,
  "intensity": 3
}
```
