package net.bruestel.homeconnect.haproxy.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import net.bruestel.homeconnect.haproxy.service.mdns.model.ConnectionType;
import net.bruestel.homeconnect.haproxy.service.mdns.model.HomeAppliance;
import net.bruestel.homeconnect.haproxy.ui.model.HomeApplianceSecrets;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;

@Slf4j
public class ProxyConfigurationView extends VBox {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProxyConfigurationView(HomeAppliance homeAppliance,
                                  Consumer<HomeApplianceSecrets> keyConsumer,
                                  Consumer<Void> cancelConsumer) {

        Label keyLabel = new Label("Key (Base64URL):");
        TextField keyField = new TextField();
        keyField.setPrefWidth(400);

        Label ivLabel = new Label("Initialization Vector (Base64URL):");
        TextField ivField = new TextField();
        ivField.setPrefWidth(400);

        Label pskLabel = new Label("PSK key (Base64URL):");
        TextField pskField = new TextField();
        pskField.setPrefWidth(400);

        Button connectButton = new Button("Start Proxy");
        connectButton.setOnAction(event -> {
            var key = keyField.getText();
            var iv = ivField.getText();
            var psk = pskField.getText();

            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Input Error");
            alert.setHeaderText("Please enter valid Base64URL encoded values.");

            if (ConnectionType.AES.equals(homeAppliance.getConnectionType())) {
                try {
                    var keyBytes = Base64.getUrlDecoder().decode(key);
                    var ivBytes = Base64.getUrlDecoder().decode(iv);

                    if (keyBytes.length != 32 || ivBytes.length != 16) {
                        log.atWarn().log("Invalid AES key or initialization vector length: key={}, iv={}", keyBytes.length, ivBytes.length);
                        alert.setContentText("Invalid AES key or initialization vector length (must be 32 bytes and 16 bytes). Please check your input and try again.");
                        alert.showAndWait();
                    } else {
                        keyConsumer.accept(new HomeApplianceSecrets(psk, key, iv));
                    }
                } catch (IllegalArgumentException e) {
                    alert.setContentText("Invalid Base64URL encoded value. Please check your input and try again.");
                    alert.showAndWait();
                }
            } else if (ConnectionType.TLS.equals(homeAppliance.getConnectionType())) {
                try {
                    var pskBytes = Base64.getUrlDecoder().decode(psk);

                    if (pskBytes.length != 32) {
                        log.atWarn().log("Invalid PSK key length: {}", pskBytes.length);
                        alert.setContentText("Invalid PSK key length (must be 32 bytes). Please check your input and try again.");
                        alert.showAndWait();
                    } else {
                        keyConsumer.accept(new HomeApplianceSecrets(psk, key, iv));
                    }
                } catch (IllegalArgumentException e) {
                    alert.setContentText("Invalid Base64URL encoded value. Please check your input and try again.");
                    alert.showAndWait();
                }
            }
        });

        Button cancelButton = new Button("Cancel");
        cancelButton.setOnAction(cancelEvent -> cancelConsumer.accept(null));

        Hyperlink loadFileButton = new Hyperlink("Extract values from \"Home Connect Profile Downloader\" profile file");
        loadFileButton.setOnAction(actionEvent -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Choose Configuration File");
            fileChooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Home Connect Profile Downloader Files", "*.zip")
            );
            File file = fileChooser.showOpenDialog(getScene().getWindow());
            var success = false;
            if (file != null) {
                try (ZipInputStream zis = new ZipInputStream(new FileInputStream(file))) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        if (entry.getName().endsWith(".json")) {
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            byte[] buffer = new byte[1024];
                            int len;
                            while ((len = zis.read(buffer)) > 0) {
                                baos.write(buffer, 0, len);
                            }

                            String jsonString = baos.toString(StandardCharsets.UTF_8);

                            JsonNode root = objectMapper.readTree(jsonString);

                            if (!root.has("connectionType") || !root.has("key")) {
                                return;
                            }

                            String connectionType = root.path("connectionType").asText();
                            String key = root.path("key").asText();
                            String iv = root.path("iv").asText();

                            if ("TLS".equals(connectionType)) {
                                pskField.setText(key);
                            } else {
                                keyField.setText(key);
                                ivField.setText(iv);
                            }
                            success = true;

                            return;
                        }
                    }
                } catch (IOException e) {
                    log.atError().log("Error reading file: {}", e.getMessage(), e);
                }

                if (!success) {
                    Alert alert = new Alert(Alert.AlertType.WARNING);
                    alert.setTitle("Profile File Error");
                    alert.setHeaderText("Could not read profile file.");
                    alert.setContentText("Please select a valid file.");
                    alert.showAndWait();
                }
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttonBar = new HBox(10, loadFileButton, spacer, cancelButton, connectButton);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);

        Label heading = new Label("Configure Secrets for Home Appliance Proxy: " + homeAppliance.getId());
        heading.setFont(Font.font("System", FontWeight.BOLD, 16));

        setSpacing(10);

        VBox fieldsBox = new VBox(10);
        if (ConnectionType.TLS.equals(homeAppliance.getConnectionType())) {
            fieldsBox.getChildren().addAll(pskLabel, pskField);
        } else {
            fieldsBox.getChildren().addAll(keyLabel, keyField, ivLabel, ivField);
        }

        setPadding(new Insets(20));
        setAlignment(Pos.TOP_LEFT);

        getChildren().addAll(
                heading,
                fieldsBox,
                buttonBar
        );
    }
}
