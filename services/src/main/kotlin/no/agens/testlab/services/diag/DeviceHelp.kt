package no.agens.testlab.services.diag

import no.agens.testlab.device.HostOs
import no.agens.testlab.services.i18n.tr

/** What to try when adb shows no phone, per OS. Shown on the Devices screen and by `testlab devices`. */
object DeviceHelp {
    fun steps(os: HostOs = HostOs.current()): List<String> = buildList {
        add(tr("On the phone: Settings → About phone → tap «Build number» seven times, then Settings → System → Developer options → turn on «USB debugging»."))
        add(tr("Use a data cable (many charging cables have no data wires) and plug it straight into the computer, not through a monitor or dock."))
        add(tr("Unlock the phone and accept «Allow USB debugging?» (tick «Always allow from this computer»). If no prompt appears: Developer options → «Revoke USB debugging authorisations», then replug."))
        add(tr("Set the USB mode to «File transfer» in the notification shade if the phone stays on «Charging only»."))
        when (os) {
            HostOs.WINDOWS -> {
                add(tr("Windows needs a USB driver: Pixel phones use Google's USB Driver (developer.android.com/studio/run/win-usb); Samsung, OnePlus, Xiaomi and others have their own OEM driver (developer.android.com/studio/run/oem-usb). In Device Manager the phone should appear under «Android Device» → «Android Composite ADB Interface»."))
                add(tr("Company laptops may block unsigned drivers or USB devices: ask IT, or use Wireless debugging instead (below)."))
            }
            HostOs.MAC -> add(tr("macOS needs no driver. If the phone shows as connected but not here, open System Settings → Privacy & Security and allow the accessory, then replug."))
            HostOs.LINUX -> {
                add(tr("Linux needs udev rules so adb may open the phone: on Debian/Ubuntu `sudo apt install android-sdk-platform-tools-common`, on Fedora/Arch the `android-udev-rules` package; then add yourself to the `plugdev` group, log out and in, and replug. «no permissions» in the list means exactly this."))
            }
        }
        add(tr("Xiaomi, OPPO and vivo also need «Install via USB» and «USB debugging (Security settings)» in Developer options before builds can be installed."))
        add(tr("No cable? Android 11+: Developer options → «Wireless debugging» → «Pair device with pairing code», then «Connect over Wi-Fi or IP…» here with the address and code it shows. The phone and computer must be on the same network (guest and company Wi-Fi often isolate devices)."))
        add(tr("Still nothing: «adb kill-server», replug, and check that only one adb is running (Android Studio ships its own; `testlab tools` shows which one {app} uses)."))
    }
}
