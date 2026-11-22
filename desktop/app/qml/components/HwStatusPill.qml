// SPDX-License-Identifier: GPL-3.0-or-later
import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import app.nectarlink

// Top bar status pill showing PC CPU load, temperature, RAM usage, laptop battery level,
// and Smart Anchor / Perimeter Guard status.
Rectangle {
    id: hwPill

    readonly property int cpu: Math.max(0, Math.min(100, AppController.pcCpuUsage))
    readonly property int ram: Math.max(0, Math.min(100, AppController.pcRamUsage))
    readonly property int temp: Math.max(20, Math.min(110, AppController.pcTemperature > 0 ? AppController.pcTemperature : 42))
    readonly property int battery: Math.max(0, Math.min(100, AppController.pcBatteryLevel))
    readonly property bool charging: AppController.pcBatteryCharging
    readonly property bool hasBattery: AppController.pcHasBattery
    readonly property bool guardArmed: AppController.perimeterGuardEnabled

    readonly property color tempColor: temp < 60 ? (Theme.dark ? "#81C784" : "#2E7D32")
                                     : temp < 75 ? (Theme.dark ? "#FFB74D" : "#EF6C00")
                                     : (Theme.dark ? "#E57373" : "#C62828")

    height: 30
    width: contentRow.implicitWidth + 20
    radius: height / 2

    color: pillMouse.containsMouse
        ? (Theme.dark ? Qt.rgba(1, 1, 1, 0.12) : Qt.rgba(0, 0, 0, 0.08))
        : (Theme.dark ? Qt.rgba(1, 1, 1, 0.07) : Qt.rgba(0, 0, 0, 0.04))
    border.width: 1
    border.color: guardArmed
        ? Qt.rgba(0.2, 0.7, 0.4, 0.5)
        : (Theme.dark ? Qt.rgba(1, 1, 1, 0.12) : Qt.rgba(0, 0, 0, 0.10))

    Behavior on color { ColorAnimation { duration: 150 } }
    Behavior on border.color { ColorAnimation { duration: 150 } }

    Row {
        id: contentRow
        anchors.centerIn: parent
        spacing: 6

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
                size: 11
                color: Theme.surfaceVariantContent
                opacity: 0.5
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
                color: Theme.surfaceVariantContent
                opacity: 0.75
                anchors.verticalCenter: parent.verticalCenter
            }
            Txt {
                text: hwPill.cpu + "%"
                role: "caption"
                size: 11
                weight: Font.DemiBold
                color: hwPill.cpu > 80 ? Theme.error : Theme.surfaceContent
                anchors.verticalCenter: parent.verticalCenter
            }
        }

        Txt {
            text: "•"
            role: "body"
            size: 11
            color: Theme.surfaceVariantContent
            opacity: 0.5
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
                color: Theme.surfaceVariantContent
                opacity: 0.75
                anchors.verticalCenter: parent.verticalCenter
            }
            Txt {
                text: hwPill.ram + "%"
                role: "caption"
                size: 11
                weight: Font.DemiBold
                color: hwPill.ram > 85 ? Theme.error : Theme.surfaceContent
                anchors.verticalCenter: parent.verticalCenter
            }
        }

        Txt {
            text: "•"
            role: "body"
            size: 11
            color: Theme.surfaceVariantContent
            opacity: 0.5
            anchors.verticalCenter: parent.verticalCenter
        }

        // Temperature
        Row {
            spacing: 2
            anchors.verticalCenter: parent.verticalCenter
            Txt {
                text: hwPill.temp + "°C"
                role: "caption"
                size: 11
                weight: Font.DemiBold
                color: hwPill.tempColor
                anchors.verticalCenter: parent.verticalCenter
            }
        }

        // Battery (if PC has a battery or is laptop)
        Row {
            visible: hwPill.hasBattery
            spacing: 4
            anchors.verticalCenter: parent.verticalCenter

            Txt {
                text: "•"
                role: "body"
                size: 11
                color: Theme.surfaceVariantContent
                opacity: 0.5
                anchors.verticalCenter: parent.verticalCenter
            }

            Txt {
                text: (hwPill.charging ? "⚡ " : "🔋 ") + hwPill.battery + "%"
                role: "caption"
                size: 11
                weight: Font.DemiBold
                color: (!hwPill.charging && hwPill.battery < 20) ? Theme.error : Theme.surfaceContent
                anchors.verticalCenter: parent.verticalCenter
            }
        }
    }

    MouseArea {
        id: pillMouse
        anchors.fill: parent
        hoverEnabled: true
        cursorShape: Qt.PointingHandCursor
        onClicked: hwPopup.open()
    }

    Popup {
        id: hwPopup
        y: hwPill.height + 6
        x: Math.min(0, -(width - hwPill.width))
        width: 260
        padding: 14
        modal: false
        focus: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

        background: Rectangle {
            color: Theme.surfaceContentElevated || (Theme.dark ? "#23262A" : "#FFFFFF")
            radius: 12
            border.width: 1
            border.color: Theme.dark ? Qt.rgba(1, 1, 1, 0.15) : Qt.rgba(0, 0, 0, 0.12)
        }

        contentItem: Column {
            spacing: 12

            Txt {
                text: qsTr("PC Hardware & Security")
                role: "headline"
                size: 14
                weight: Font.Bold
            }

            // Stats grid
            Grid {
                columns: 2
                columnSpacing: 16
                rowSpacing: 8
                width: parent.width

                Txt { text: qsTr("CPU Load:"); color: Theme.surfaceVariantContent; size: 12 }
                Txt { text: hwPill.cpu + "%"; weight: Font.DemiBold; size: 12 }

                Txt { text: qsTr("Memory (RAM):"); color: Theme.surfaceVariantContent; size: 12 }
                Txt { text: hwPill.ram + "% in use"; weight: Font.DemiBold; size: 12 }

                Txt { text: qsTr("Temperature:"); color: Theme.surfaceVariantContent; size: 12 }
                Txt { text: hwPill.temp + " °C"; color: hwPill.tempColor; weight: Font.DemiBold; size: 12 }

                Txt {
                    visible: hwPill.hasBattery
                    text: qsTr("Battery:"); color: Theme.surfaceVariantContent; size: 12
                }
                Txt {
                    visible: hwPill.hasBattery
                    text: hwPill.battery + "% " + (hwPill.charging ? qsTr("(Plugged in / Charging)") : qsTr("(On battery)"));
                    weight: Font.DemiBold
                    size: 12
                }
            }

            Rectangle {
                width: parent.width
                height: 1
                color: Theme.dark ? Qt.rgba(1, 1, 1, 0.1) : Qt.rgba(0, 0, 0, 0.08)
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
                        color: Theme.surfaceVariantContent
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
