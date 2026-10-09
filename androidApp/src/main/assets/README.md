VRM mode's tracking model files (face_landmarker.task, hand_landmarker.task,
pose_landmarker_full.task — Google MediaPipe, Apache 2.0) no longer ship in
the APK. They're downloaded the first time VRM mode opens and kept in the
app's private storage — see shared/src/androidMain/.../util/TrackingModels.kt
for the exact URLs (float16, version 1: the same files that used to be here).

If the files are ever put back in this folder, the app uses them instead of
downloading (TrackingModels checks the assets first).
