package net.bruestel.homeconnect.haproxy.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import net.bruestel.homeconnect.haproxy.service.mdns.model.HomeAppliance;
import net.bruestel.homeconnect.haproxy.ui.model.LogEntry;
import net.bruestel.homeconnect.haproxy.ui.model.Sender;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

@Slf4j
public class LogView extends VBox {

    private final ObjectWriter objectWriter;
    @Getter
    private final ObservableList<LogEntry> logEntries = FXCollections.observableArrayList();

    public LogView(Stage stage, HomeAppliance homeAppliance, Consumer<Void> cancelEvent) {
        var objectMapper = new ObjectMapper();
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.registerModule(new JavaTimeModule());
        objectWriter = objectMapper.writerWithDefaultPrettyPrinter();

        Label title = new Label("Log: " + homeAppliance.getId());
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");

        Button cancelButton = new Button("Cancel");
        Button exportButton = new Button("Export Log");

        cancelButton.setOnAction(actionEvent -> cancelEvent.accept(null));
        exportButton.setOnAction(logExportActionEvent -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Save Message Log");
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON Files", "*.json"));
            fileChooser.setInitialFileName("websocket-messages-" + homeAppliance.getId().toLowerCase() + "-" + System.currentTimeMillis() + ".json");

            File file = fileChooser.showSaveDialog(stage);
            if (file != null) {
                try (FileWriter writer = new FileWriter(file)) {
                    writer.write(objectWriter.writeValueAsString(logEntries));
                } catch (IOException ioException) {
                    log.atError().log("Error writing log file: {}", ioException.getMessage(), ioException);
                }
            }
        });

        HBox buttonBar = new HBox(10, exportButton, cancelButton);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);

        HBox topBar = new HBox();
        HBox.setHgrow(buttonBar, Priority.ALWAYS);
        topBar.getChildren().addAll(title, new Region(), buttonBar);
        topBar.setPadding(new Insets(10));
        topBar.setSpacing(10);
        topBar.setAlignment(Pos.CENTER_LEFT);

        TableView<LogEntry> tableView = new TableView<>();
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        TableColumn<LogEntry, ZonedDateTime> timestampCol = new TableColumn<>("Timestamp");
        timestampCol.setCellValueFactory(new PropertyValueFactory<>("timestamp"));
        timestampCol.setMinWidth(170);
        timestampCol.setMaxWidth(170);
        timestampCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(ZonedDateTime item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    try {
                        setText(item.format(formatter));
                    } catch (Exception e) {
                        setText(item.toString()); // fallback
                    }
                }
            }
        });

        TableColumn<LogEntry, Object> sessionCol = new TableColumn<>("Session");
        sessionCol.setCellValueFactory(new PropertyValueFactory<>("sessionId"));
        sessionCol.setMinWidth(70);
        sessionCol.setMaxWidth(70);

        TableColumn<LogEntry, Sender> directionCol = new TableColumn<>("");
        directionCol.setCellValueFactory(new PropertyValueFactory<>("sender"));
        directionCol.setMinWidth(20);
        directionCol.setMaxWidth(20);
        directionCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Sender item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    try {
                        setText(Sender.HOME_APPLIANCE.equals(item) ? "\uD83C\uDFE0" : "\uD83D\uDCF1");
                    } catch (Exception e) {
                        setText(item.toString()); // fallback
                    }
                }
            }
        });

        TableColumn<LogEntry, Object> messageCol = new TableColumn<>("Message");
        messageCol.setCellValueFactory(new PropertyValueFactory<>("message"));

        Label emptyTablePlaceholder = new Label("Waiting for messages...");
        emptyTablePlaceholder.setStyle("-fx-font-style: italic; -fx-text-fill: grey;");
        tableView.setPlaceholder(emptyTablePlaceholder);
        tableView.setFixedCellSize(-1);

        // JSON pretty print
        messageCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    try {
                        setText(objectWriter.writeValueAsString(item));
                    } catch (Exception e) {
                        setText(item.toString()); // fallback
                    }
                }
            }
        });

        //noinspection unchecked
        tableView.getColumns().addAll(directionCol, timestampCol, sessionCol, messageCol);
        tableView.setItems(logEntries);

        VBox.setVgrow(tableView, Priority.ALWAYS);
        setPadding(new Insets(10));
        setSpacing(10);
        getChildren().addAll(topBar, tableView);
    }
}
