import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ApplicationWindow {
    id: window
    width: 700
    height: 500
    visible: true
    title: "Qt guest editor"
    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 24
        spacing: 12
        Label { text: "Qt text-input-v3" }
        TextField { Layout.fillWidth: true; placeholderText: "Text"; focus: true }
        TextField { Layout.fillWidth: true; placeholderText: "Email"; inputMethodHints: Qt.ImhEmailCharactersOnly }
        TextField {
            Layout.fillWidth: true
            placeholderText: "PIN"
            echoMode: TextInput.Password
            inputMethodHints: Qt.ImhDigitsOnly | Qt.ImhHiddenText | Qt.ImhSensitiveData
        }
        Button { text: "Copy"; focusPolicy: Qt.StrongFocus }
        Button { text: "Paste"; focusPolicy: Qt.StrongFocus }
        Button { text: "Drag"; focusPolicy: Qt.StrongFocus }
        Button { text: "Open dialog"; focusPolicy: Qt.StrongFocus; onClicked: dialog.show() }
        Item { Layout.fillHeight: true }
    }
    Window {
        id: dialog
        title: "Qt child dialog"
        width: 340
        height: 180
        maximumWidth: 340
        maximumHeight: 180
        transientParent: window
        modality: Qt.WindowModal
        ColumnLayout {
            anchors.centerIn: parent
            TextField { placeholderText: "Child editor" }
            Button { text: "Nested dialog"; onClicked: nested.show() }
            Button { text: "Close"; onClicked: dialog.hide() }
        }
        Window {
            id: nested
            title: "Qt nested dialog"
            width: 260
            height: 120
            maximumWidth: 260
            maximumHeight: 120
            transientParent: dialog
            modality: Qt.WindowModal
            Button { anchors.centerIn: parent; text: "Close nested"; onClicked: nested.hide() }
        }
    }
}
