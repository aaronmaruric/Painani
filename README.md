# Painani

A personal training log for Android: GPS runs with splits, strength workouts with sets, and a
calendar that shows both at a glance.

Licensed under **GPLv3** (see `LICENSE`). Parts of the tracking and calendar code are intended to be
ported from [RunnerUp](https://github.com/jonasoreland/runnerup),
[Fossify Calendar](https://github.com/FossifyOrg/Calendar) and
[Flexify](https://github.com/brandonp2412/Flexify), all GPLv3.

## Building

Requires Android Studio (Ladybug or newer). Open the project folder; Studio installs the SDK and
JDK it needs. From the command line, once `local.properties` points at an SDK:

```
./gradlew assembleDebug
./gradlew test
```

## Layout

```
app/src/main/java/com/painani/app/
├── domain/          Pure Kotlin — models + repository interface. No Android imports.
│   ├── model/       Session, Split, Exercise, ExerciseSet, CalendarEvent, UserProfile, WeightEntry
│   └── repository/  SessionRepository, CalendarEventRepository, ProfileRepository, BodyStatsRepository
├── data/            Room implementation of the domain repository.
│   ├── local/       Entities, DAO, database
│   ├── repository/  Room/DataStore repositories (+ entity<->domain mappers)
│   └── health/      Health Connect client wrapper and sync policy
├── ics/             Pure Kotlin iCalendar parser (folding, TZID, DURATION, RRULE expansion)
├── tracking/        GPS engine: RunTracker interface, LocationManager implementation, foreground service
├── ui/
│   ├── calendar/    Day / week / month / list views; day detail with splits, sets and planned events
│   ├── run/         Live GPS run tracking, plus manual entry
│   ├── strength/    Workout logging (exercise / reps / weight rows)
│   ├── settings/    Profile, weight log, .ics import
│   ├── navigation/  Bottom-nav host and routes
│   └── theme/       Material 3 theme
├── PainaniApp.kt   Application + hand-rolled DI container
└── MainActivity.kt
```

The `domain` package is deliberately platform-free so it can move into a shared Kotlin
Multiplatform module if an iOS build is ever wanted.

## Roadmap

- [x] Live GPS tracking with km splits (LocationManager, foreground service, wake lock)
- [x] Import .ics calendars (training plans, races) and show them alongside sessions
- [x] Day / week / month / agenda calendar views
- [x] Profile and weight log
- [x] Exercise picker + rest timer on the strength screen
- [x] Edit / delete imported calendar events
- [x] Health Connect: heart rate on runs and splits, daily steps / resting HR / sleep, two-way weight sync, sessions written back
- [x] Import watch workouts (Samsung Health, Garmin ...) from Health Connect, with laps as splits and routes on request
- [ ] Per-exercise and per-distance progress charts
- [ ] GPX/TCX export
