import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ApplicationWindow {
    id: window
    width: 720
    height: 480
    visible: true
    title: "Wayland Qt GPU validation"
    menuBar: MenuBar {
        Menu {
            title: "File"
            popupType: Popup.Window
            Action { text: "Dialog"; onTriggered: dialog.open() }
            Action { text: "Close"; onTriggered: window.close() }
        }
    }
    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 24
        TextField { Layout.fillWidth: true; placeholderText: "Keyboard and clipboard" }
        RowLayout {
            Button { text: "Dialog"; onClicked: dialog.open() }
            Button { text: "Count: " + count; property int count: 0; onClicked: { ++count; console.log("click", count) } }
            Slider { id: opacity; from: 0.1; to: 1; value: 0.7 }
        }
        Rectangle {
            Layout.fillWidth: true
            Layout.fillHeight: true
            color: "#eeeeee"
            Rectangle {
                anchors.centerIn: parent
                width: 130
                height: 130
                color: "#179f9a"
                opacity: opacity.value
                NumberAnimation on rotation { from: 0; to: 360; duration: 3000; loops: Animation.Infinite }
            }
        }
    }
    Dialog {
        id: dialog
        title: "Dependent surface"
        anchors.centerIn: parent
        modal: true
        popupType: Popup.Window
        standardButtons: Dialog.Ok | Dialog.Cancel
        Label { text: "Popup input and transparency" }
        onAccepted: console.log("dialog accepted")
        onRejected: console.log("dialog rejected")
    }
}
