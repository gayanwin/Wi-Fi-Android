SHARE TO PHONE - ANDROID APP (online build)
===========================================

Android Studio onema naha. GitHub (free) eka thamai APK eka build karanne.

1) https://github.com ekata free account ekak hadaganna (thiyenawanam login wenna).
2) Uda dakunu kone "+" > "New repository". Name: sharetophone  >  Create repository.
3) "uploading an existing file" link eka click karanna.
   Me zip eka Extract karala athule thiyena okkoma files/folders (app, .github, build.gradle, settings.gradle, gradle.properties)
   browser eke drag & drop karanna  >  Commit changes.
   NOTE: ".github" folder eka drag karama nodena nam:
     "Add file > Create new file" > name eke  .github/workflows/build.yml  kiyala type karanna
     > me zip eke thiyena build.yml eke text eka paste karala Commit.
4) "Actions" tab eka > "Build APK" > "Run workflow" (ho commit ekata auto start wenawa). 3-5 minutes.
5) Green tick eka awama run eka click karanna > yata "Artifacts" athule "ShareToPhone-APK" download karanna (zip eka).
   Zip eka extract karala app-debug.apk eka phone ekata dala install karanna.
   (Phone eke "Install unknown apps" allow karanna ona wenawa.)

Fail unoth: Actions eke red run eka click karala error log eka (screenshot ekak) mata yawanna.

App eka use karana widiha
   - App eka open karala "Scan QR code" tap karanna, PC eke QR eka scan karanna (eka paarak withrai).
   - Eken passe app eka open karapu gaman files pennanawa. Downloads app eken thamai wenne: notification eka app ekenma enawa (progress bar, % , speed, time left, Cancel button).
     Download eka connections 6k eken (parallel) wenawa, ethakota single connection ekata wada speed eka wadi wenna puluwan.
     Files save wenne  Downloads/Share to Phone  folder ekata. Notification eka tap kalama file eka open wenawa.
