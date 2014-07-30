EventRecordAndReplay
====================

It's a small tool to setup automatic testing. It can record any input and rotation event and then replay these recorded events. So that you can use it to create automatic test cases.

This apk must be signed with platform certification to run. If you want to use the prebuilt EventRecordAndReplay.apk you must re-sign it with your platform certification.

Start Recording:
$adb shell am broadcast --user current -a com.powermo.inputrecord.action -e command start

Stop Recording:
$adb shell am broadcast --user current -a com.powermo.inputrecord.action -e command stop

Replayback recorded event:
$adb shell am broadcast --user current -a com.powermo.inputrecord.action -e command replay

By default you will find /sdcard/powermo.rec as the saved record event file. You can also pass into one extra parameter "-e file /sdcard/xxx.rec" for start and replay command to specify a file.
