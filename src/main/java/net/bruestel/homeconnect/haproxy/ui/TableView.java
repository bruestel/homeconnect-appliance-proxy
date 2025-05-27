package net.bruestel.homeconnect.haproxy.ui;

import lombok.Getter;
import lombok.Setter;

import net.bruestel.homeconnect.haproxy.service.mdns.model.ConnectionType;
import net.bruestel.homeconnect.haproxy.service.mdns.model.HomeAppliance;

import java.net.InetAddress;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.VBox;

public class TableView extends VBox {

    @Getter
    private final ObservableList<HomeAppliance> homeAppliances = FXCollections.observableArrayList();
    private final javafx.scene.control.TableView<HomeAppliance> tableViewElement;
    @Setter
    private Consumer<HomeAppliance> homeApplianceSelectedAction;

    public TableView() {
        tableViewElement = new javafx.scene.control.TableView<>();

        TableColumn<HomeAppliance, String> idCol = new TableColumn<>("ID");
        idCol.setCellValueFactory(new PropertyValueFactory<>("id"));
        idCol.setMinWidth(230);
        TableColumn<HomeAppliance, String> brandCol = new TableColumn<>("Brand");
        brandCol.setCellValueFactory(new PropertyValueFactory<>("brand"));
        TableColumn<HomeAppliance, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(new PropertyValueFactory<>("type"));
        TableColumn<HomeAppliance, String> vibCol = new TableColumn<>("VIB");
        vibCol.setCellValueFactory(new PropertyValueFactory<>("vib"));

        TableColumn<HomeAppliance, String> addressCol = new TableColumn<>("Address");
        addressCol.setCellValueFactory(cellData -> new ReadOnlyStringWrapper(cellData.getValue()
                .getAddressSet()
                .stream()
                .map(InetAddress::getHostAddress)
                .collect(Collectors.joining("\n"))));
        addressCol.setMinWidth(250);

        TableColumn<HomeAppliance, ConnectionType> connectionTypeCol = new TableColumn<>("Connection Type");
        connectionTypeCol.setCellValueFactory(new PropertyValueFactory<>("connectionType"));
        addressCol.setMinWidth(5);


        TableColumn<HomeAppliance, Void> actionCol = new TableColumn<>("Action");
        actionCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    Button setupProxyButton = new Button("Setup Proxy");
                    setupProxyButton.setOnAction(event -> {
                        HomeAppliance homeAppliance = tableViewElement.getItems().get(getIndex());
                        if (homeApplianceSelectedAction != null) {
                            homeApplianceSelectedAction.accept(homeAppliance);
                        }
                    });

                    setupProxyButton.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

                    setGraphic(setupProxyButton);
                }
            }
        });
        actionCol.setMinWidth(100);

        //noinspection unchecked
        tableViewElement.getColumns().addAll(idCol, brandCol, typeCol, vibCol, addressCol, connectionTypeCol, actionCol);
        tableViewElement.setMinHeight(30);
        tableViewElement.setSelectionModel(null);
        tableViewElement.setColumnResizePolicy(javafx.scene.control.TableView.CONSTRAINED_RESIZE_POLICY_LAST_COLUMN);

        tableViewElement.setItems(homeAppliances);

        Label emptyTablePlaceholder = new Label("Scanning the network for Home Connect appliances. Please wait a moment...");
        emptyTablePlaceholder.setStyle("-fx-font-style: italic; -fx-text-fill: grey;");
        tableViewElement.setPlaceholder(emptyTablePlaceholder);

        VBox.setVgrow(tableViewElement, javafx.scene.layout.Priority.ALWAYS);
        getChildren().addAll(tableViewElement);
    }

    public void refresh() {
        tableViewElement.refresh();
    }
}
