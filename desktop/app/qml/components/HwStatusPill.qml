// SPDX-License-Identifier: GPL-3.0-or-later
import QtQuick
import QtQuick.Layouts
import app.nectarlink

// Top bar status pill showing PC CPU load, temperature, RAM usage, laptop battery level,
// and Smart Anchor / Perimeter Guard status with crystal-clear high-contrast styling.
Rectangle {
    id: hwPill

    property bool popupVisible: false

    readonly property int cpu: Math.max(0, Math.min(100, AppController.pcCpuUsage))
    readonly property int ram: Math.max(0, Math.min(100, AppController.pcRamUsage))
    readonly property bool hasTemp: AppController.pcTemperature > 0
    readonly property int temp: AppController.pcTemperature > 0 ? AppController.pcTemperature : 43
    readonly property int battery: Math.max(0, Math.min(100, AppController.pcBatteryLevel))
    readonly property bool charging: AppController.pcBatteryCharging
    readonly property bool hasBattery: AppController.pcHasBattery
    readonly property bool guardArmed: AppController.perimeterGuardEnabled

    readonly property color tempColor: temp < 60 ? "#4CAF50"
                                     : temp < 75 ? "#FF9800"
                                     : "#F44336"

    // Crisp high-contrast color roles matching native top-bar action buttons
    readonly property color labelColor: Theme.surfaceContentVariant
    readonly property color valueColor: Theme.surfaceContent
    readonly property color sepColor: Theme.dark ? Qt.rgba(1, 1, 1, 0.25) : Qt.rgba(0, 0, 0, 0.20)

    height: 34
    width: contentRow.implicitWidth + 20
    radius: Theme.pill(height)

    color: "transparent"
    border.width: guardArmed ? 1 : 0
    border.color: guardArmed ? Qt.rgba(0.29, 0.69, 0.31, 0.8) : "transparent"

    // Smooth native top-bar hover highlight matching Commands and Timeline buttons
    Rectangle {
        anchors.fill: parent
        radius: parent.radius
        color: Theme.surfaceContent
        opacity: pillMouse.pressed ? 0.14 : (pillMouse.containsMouse ? 0.08 : 0)
        Behavior on opacity { NumberAnimation { duration: Theme.fadeFast } }
    }



    Row {
        id: contentRow
        anchors.centerIn: parent
        spacing: 5

        // Perimeter guard indicator if armed
        Row {
            visible: hwPill.guardArmed
            spacing: 3
            anchors.verticalCenter: parent.verticalCenter
            Txt {
                text: "🛡️"
                size: 11
                anchors.verticalCenter: parent.verticalCenter
            }
            Txt {
                text: "•"
                role: "body"
                size: 10
                color: hwPill.sepColor
                anchors.verticalCenter: parent.verticalCenter
            }
        }

        // CPU
        Row {
            spacing: 2
            anchors.verticalCenter: parent.verticalCenter
            Txt {
                text: qsTr("CPU")
                role: "caption"
                size: 10
                weight: Font.Bold
                color: hwPill.labelColor
                anchors.verticalCenter: parent.verticalCenter
            }
            Txt {
                text: hwPill.cpu + "%"
                role: "caption"
                size: 11
                weight: Font.Bold
                color: hwPill.cpu > 80 ? Theme.error : hwPill.valueColor
                anchors.verticalCenter: parent.verticalCenter
            }
        }

        Txt {
            text: "•"
            role: "body"
            size: 10
            color: hwPill.sepColor
            anchors.verticalCenter: parent.verticalCenter
        }

        // RAM
        Row {
            spacing: 2
            anchors.verticalCenter: parent.verticalCenter
            Txt {
                text: qsTr("RAM")
                role: "caption"
                size: 10
                weight: Font.Bold
                color: hwPill.labelColor
                anchors.verticalCenter: parent.verticalCenter
            }
            Txt {
                text: hwPill.ram + "%"
                role: "caption"
                size: 11
                weight: Font.Bold
                color: hwPill.ram > 85 ? Theme.error : hwPill.valueColor
                anchors.verticalCenter: parent.verticalCenter
            }
        }

        // Temperature (only displayed when genuine hardware thermal sensor is available)
        Row {
            visible: hwPill.hasTemp
            spacing: 5
            anchors.verticalCenter: parent.verticalCenter

            Txt {
                text: "•"
                role: "body"
                size: 10
                color: hwPill.sepColor
                anchors.verticalCenter: parent.verticalCenter
            }

            Txt {
                text: hwPill.temp + "°C"
                role: "caption"
                size: 11
                weight: Font.Bold
                color: hwPill.tempColor
                anchors.verticalCenter: parent.verticalCenter
            }
        }

        // Power / Battery (shows percentage if laptop battery exists, or AC state if desktop)
        Row {
            spacing: 3
            anchors.verticalCenter: parent.verticalCenter

            Txt {
                text: "•"
                role: "body"
                size: 10
                color: hwPill.sepColor
                anchors.verticalCenter: parent.verticalCenter
            }

            Txt {
                text: hwPill.charging ? "⚡" : "🔋"
                size: hwPill.charging ? 11 : 10
                color: hwPill.charging ? "#FFC107" : hwPill.valueColor
                anchors.verticalCenter: parent.verticalCenter
            }

            Txt {
                text: hwPill.hasBattery
                    ? (hwPill.battery + "%")
                    : (hwPill.charging ? "AC" : "Plugged")
                role: "caption"
                size: 11
                weight: Font.Bold
                color: (hwPill.hasBattery && !hwPill.charging && hwPill.battery < 20) ? Theme.error : hwPill.valueColor
                anchors.verticalCenter: parent.verticalCenter
            }
        }
    }

    MouseArea {
        id: pillMouse
        anchors.fill: parent
        hoverEnabled: true
        cursorShape: Qt.PointingHandCursor
        onClicked: hwPill.popupVisible = !hwPill.popupVisible
    }

    Rectangle {
        id: hwPopup
        visible: hwPill.popupVisible
        z: 9999
        y: hwPill.height + 6
        x: Math.min(0, -(width - hwPill.width))
        width: 270
        height: popupCol.implicitHeight + 28
        radius: 12
        color: Theme.dark ? "#23262A" : "#FFFFFF"
        border.width: 1
        border.color: Theme.dark ? Qt.rgba(1, 1, 1, 0.18) : Qt.rgba(0, 0, 0, 0.14)

        Column {
            id: popupCol
            anchors.fill: parent
            anchors.margins: 14
            spacing: 12

            RowLayout {
                width: parent.width
                Txt {
                    text: qsTr("PC Hardware & Security")
                    role: "headline"
                    size: 13
                    weight: Font.Bold
                    Layout.fillWidth: true
                }
                IconButton {
                    iconPath: Icons.close
                    iconSize: 14
                    onClicked: hwPill.popupVisible = false
                }
            }

            // Stats grid
            Grid {
                columns: 2
                columnSpacing: 16
                rowSpacing: 8
                width: parent.width

                Txt { text: qsTr("CPU Load:"); color: hwPill.labelColor; size: 12 }
                Txt { text: hwPill.cpu + "%"; color: hwPill.valueColor; weight: Font.DemiBold; size: 12 }

                Txt { text: qsTr("Memory (RAM):"); color: hwPill.labelColor; size: 12 }
                Txt { text: hwPill.ram + "% in use"; color: hwPill.valueColor; weight: Font.DemiBold; size: 12 }

                Txt { text: qsTr("Temperature:"); color: hwPill.labelColor; size: 12 }
                Txt {
                    text: hwPill.hasTemp ? (hwPill.temp + " °C") : qsTr("Not exposed by BIOS");
                    color: hwPill.hasTemp ? hwPill.tempColor : hwPill.labelColor;
                    weight: Font.DemiBold;
                    size: 12
                }

                Txt {
                    visible: hwPill.hasBattery
                    text: qsTr("Battery:"); color: hwPill.labelColor; size: 12
                }
                Txt {
                    visible: hwPill.hasBattery
                    text: hwPill.battery + "% " + (hwPill.charging ? qsTr("(Plugged in / Charging)") : qsTr("(On battery)"));
                    color: hwPill.valueColor
                    weight: Font.DemiBold
                    size: 12
                }
            }

            Rectangle {
                width: parent.width
                height: 1
                color: Theme.dark ? Qt.rgba(1, 1, 1, 0.12) : Qt.rgba(0, 0, 0, 0.08)
            }

            // Perimeter Guard (20-ft Smart Anchor) toggle
            RowLayout {
                width: parent.width
                spacing: 8

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 2
                    Txt {
                        text: qsTr("Perimeter Guard")
                        role: "label"
                        size: 13
                        weight: Font.DemiBold
                    }
                    Txt {
                        text: hwPill.guardArmed
                            ? qsTr("Armed: Alerts phone if PC moves 20 ft or power is pulled")
                            : qsTr("20-ft displacement & power anti-theft alert")
                        role: "caption"
                        size: 10
                        wrapMode: Text.Wrap
                        Layout.fillWidth: true
                        color: hwPill.labelColor
                    }
                }

                Button {
                    text: hwPill.guardArmed ? qsTr("Armed") : qsTr("Arm")
                    variant: hwPill.guardArmed ? "filled" : "outlined"
                    onClicked: AppController.togglePerimeterGuard()
                }
            }
        }
    }
}
