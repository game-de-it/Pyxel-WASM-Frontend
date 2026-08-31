# pwf — Android app

The build lives here. The documentation moved:

- [User guide](../docs/usage.md) — installing games, playing them, every setting
- [Developer guide](../docs/development.md) — architecture, build steps, why things are the way they are

```sh
sh ../tools/fetch-runtime.sh                                   # once
python3 ../tools/make-bundle.py 4 app/src/main/assets/runtime   # once
./gradlew :app:assembleRelease
```
