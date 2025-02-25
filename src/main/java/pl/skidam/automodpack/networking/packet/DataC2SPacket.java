package pl.skidam.automodpack.networking.packet;

import io.netty.buffer.Unpooled;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.network.PacketByteBuf;
import pl.skidam.automodpack.mixin.core.ClientConnectionAccessor;
import pl.skidam.automodpack.mixin.core.ClientLoginNetworkHandlerAccessor;
import pl.skidam.automodpack.networking.content.DataPacket;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_loader_core.ReLauncher;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class DataC2SPacket {
    public static CompletableFuture<PacketByteBuf> receive(MinecraftClient minecraftClient, ClientLoginNetworkHandler handler, PacketByteBuf buf) {
        String serverResponse = buf.readString(Short.MAX_VALUE);

        DataPacket dataPacket = DataPacket.fromJson(serverResponse);
        String packetAddress = dataPacket.address;
        Integer packetPort = dataPacket.port;
        boolean modRequired = dataPacket.modRequired;

        if (modRequired) {
            // TODO set screen to refreshed danger screen which will ask user to install modpack with two options
            // 1. Disconnect and install modpack
            // 2. Dont disconnect and join server
        }

        InetSocketAddress address = (InetSocketAddress) ((ClientLoginNetworkHandlerAccessor) handler).getConnection().getAddress();

        if (packetAddress.isBlank()) {
            LOGGER.info("Address from connected server: {}:{}", address.getAddress().getHostName(), address.getPort());
        } else if (packetPort != null) {
            address = new InetSocketAddress(packetAddress, packetPort);
            LOGGER.info("Received address packet from server! {}:{}", packetAddress, packetPort);
        } else {
            var portIndex = packetAddress.lastIndexOf(':');
            var port = portIndex == -1 ? 0 : Integer.parseInt(packetAddress.substring(portIndex + 1));
            var addressString = portIndex == -1 ? packetAddress : packetAddress.substring(0, portIndex);
            address = new InetSocketAddress(addressString, port);
            LOGGER.info("Received address packet from server! {} Attached port: {}", addressString, port);
        }

        // save secret
        Secrets.Secret secret = dataPacket.secret;
        SecretsStore.saveClientSecret(clientConfig.selectedModpack, secret);

        Boolean needsDisconnecting = null;

        Path modpackDir = ModpackUtils.getModpackPath(address, dataPacket.modpackName);
        var optionalServerModpackContent = ModpackUtils.requestServerModpackContent(address, secret);

        if (optionalServerModpackContent.isPresent()) {
            boolean update = ModpackUtils.isUpdate(optionalServerModpackContent.get(), modpackDir);

            if (update) {
                disconnectImmediately(handler);
                new ModpackUpdater().prepareUpdate(optionalServerModpackContent.get(), address, secret, modpackDir);
                needsDisconnecting = true;
            } else {
                boolean selectedModpackChanged = ModpackUtils.selectModpack(modpackDir, address, Set.of());
                if (selectedModpackChanged) {
                    disconnectImmediately(handler);
                    // Its needed since newly selected modpack may not be loaded
                    new ReLauncher(modpackDir, UpdateType.SELECT).restart(false);
                    needsDisconnecting = true;
                } else {
                    needsDisconnecting = false;
                }
            }
        }

        PacketByteBuf response = new PacketByteBuf(Unpooled.buffer());
        response.writeString(String.valueOf(needsDisconnecting), Short.MAX_VALUE);

        return CompletableFuture.completedFuture(response);

    }

    private static void disconnectImmediately(ClientLoginNetworkHandler clientLoginNetworkHandler) {
        ((ClientConnectionAccessor) ((ClientLoginNetworkHandlerAccessor) clientLoginNetworkHandler).getConnection()).getChannel().disconnect();
    }
}
