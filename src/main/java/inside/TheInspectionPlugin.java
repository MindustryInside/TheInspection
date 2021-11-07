package inside;

import arc.Events;
import arc.graphics.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import arc.util.serialization.Base64Coder;
import mindustry.core.*;
import mindustry.game.EventType;
import mindustry.gen.*;
import mindustry.mod.Plugin;
import mindustry.net.*;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;

import static arc.util.Log.*;
import static mindustry.Vars.*;
import static mindustry.net.Packets.*;

public class TheInspectionPlugin extends Plugin{
    private ReusableByteOutStream writeBuffer;
    private Writes outputBuffer;

    @Override
    public void init(){
        writeBuffer = Reflect.get(NetServer.class, netServer, "writeBuffer");
        outputBuffer = Reflect.get(NetServer.class, netServer, "outputBuffer");

        net.handleServer(ConnectPacket.class, (con, packet) -> {
            if(con.kicked) return;

            if(con.address.startsWith("steam:")){
                packet.uuid = con.address.substring("steam:".length());
            }

            con.connectTime = Time.millis();

            String uuid = packet.uuid;
            byte[] buuid = Base64Coder.decode(uuid);
            CRC32 crc = new CRC32();
            crc.update(buuid, 0, 8);
            ByteBuffer buff = ByteBuffer.allocate(8);
            buff.put(buuid, 8, 8);
            buff.position(0);
            if(crc.getValue() != buff.getLong()){
                con.kick(KickReason.clientOutdated);
                return;
            }

            if(netServer.admins.isIPBanned(con.address) || netServer.admins.isSubnetBanned(con.address)) return;

            if(con.hasBegunConnecting){
                con.kick(KickReason.idInUse);
                return;
            }

            Administration.PlayerInfo info = netServer.admins.getInfo(uuid);

            con.hasBegunConnecting = true;
            con.mobile = packet.mobile;

            if(packet.uuid == null || packet.usid == null){
                con.kick(KickReason.idInUse);
                return;
            }

            if(netServer.admins.isIDBanned(uuid)){
                con.kick(KickReason.banned);
                return;
            }

            if(Time.millis() < netServer.admins.getKickTime(uuid, con.address)){
                con.kick(KickReason.recentKick);
                return;
            }

            if(netServer.admins.getPlayerLimit() > 0 && Groups.player.size() >= netServer.admins.getPlayerLimit() && !netServer.admins.isAdmin(uuid, packet.usid)){
                con.kick(KickReason.playerLimit);
                return;
            }

            Seq<String> missingMods = mods.getIncompatibility(packet.mods.copy());

            if(missingMods.any()){
                String result = "[accent]Incompatible mods![]\n\nMissing:[lightgray]\n> " + missingMods.toString("\n> ") + "[]\n";
                con.kick(result, 0);
            }

            if(!netServer.admins.isWhitelisted(packet.uuid, packet.usid)){
                info.adminUsid = packet.usid;
                info.lastName = packet.name;
                info.id = packet.uuid;
                netServer.admins.save();
                Call.infoMessage(con, "You are not whitelisted here.");
                info("&lcDo &lywhitelist-add @&lc to whitelist the player &lb'@'", packet.uuid, packet.name);
                con.kick(KickReason.whitelist);
                return;
            }

            if(packet.versionType == null || (packet.version == -1 || !packet.versionType.equals(Version.type)) && Version.build != -1 && !netServer.admins.allowsCustomClients()){
                con.kick(!Version.type.equals(packet.versionType) ? KickReason.typeMismatch : KickReason.customClient);
                return;
            }

            boolean preventDuplicates = headless && netServer.admins.isStrict();

            if(preventDuplicates){
                if(Groups.player.contains(p -> p.name.trim().equalsIgnoreCase(packet.name.trim()))){
                    con.kick(KickReason.nameInUse);
                    return;
                }

                if(Groups.player.contains(player -> player.uuid().equals(packet.uuid) || player.usid().equals(packet.usid))){
                    con.kick(KickReason.idInUse);
                    return;
                }
            }

            packet.name = fixName(packet.name);

            if(packet.name.trim().length() <= 0){
                con.kick(KickReason.nameEmpty);
                return;
            }

            if(packet.locale == null){
                packet.locale = "en";
            }

            String ip = con.address;

            netServer.admins.updatePlayerJoined(uuid, ip, packet.name);

            if(packet.version != Version.build && Version.build != -1 && packet.version != -1){
                con.kick(packet.version > Version.build ? KickReason.serverOutdated : KickReason.clientOutdated);
                return;
            }

            if(packet.version == -1){
                con.modclient = true;
            }

            Player player = Player.create();
            player.admin = netServer.admins.isAdmin(uuid, packet.usid);
            player.con = con;
            player.con.usid = packet.usid;
            player.con.uuid = uuid;
            player.con.mobile = packet.mobile;
            player.name = packet.name;
            player.locale = packet.locale;
            player.color.set(packet.color).a(1f);

            //save admin ID but don't overwrite it
            if(!player.admin && !info.admin){
                info.adminUsid = packet.usid;
            }

            try{
                writeBuffer.reset();
                player.write(outputBuffer);
            }catch(Throwable t){
                con.kick(KickReason.nameEmpty);
                err(t);
                return;
            }

            con.player = player;

            //playing in pvp mode automatically assigns players to teams
            player.team(netServer.assignTeam(player));

            netServer.sendWorldData(player);

            platform.updateRPC();

            Events.fire(new EventType.PlayerConnect(player));
        });
    }

    String fixName(String name){
        name = name.trim();
        if(name.equals("[") || name.equals("]")){
            return "";
        }

        for(int i = 0; i < name.length(); i++){
            if(name.charAt(i) == '[' && i != name.length() - 1 && name.charAt(i + 1) != '[' && (i == 0 || name.charAt(i - 1) != '[')){
                String prev = name.substring(0, i);
                String next = name.substring(i);
                String result = checkColor(next);

                name = prev + result;
            }
        }

        StringBuilder result = new StringBuilder();
        int curChar = 0;
        while(curChar < name.length() && result.toString().getBytes(Strings.utf8).length < maxNameLength){
            result.append(name.charAt(curChar++));
        }
        return result.toString();
    }

    String checkColor(String str){
        for(int i = 1; i < str.length(); i++){
            if(str.charAt(i) == ']'){
                String color = str.substring(1, i);

                if(Colors.get(color.toUpperCase()) != null || Colors.get(color.toLowerCase()) != null){
                    Color result = Colors.get(color.toLowerCase()) == null ? Colors.get(color.toUpperCase()) : Colors.get(color.toLowerCase());
                    if(result.a <= 0.8f){
                        return str.substring(i + 1);
                    }
                }else{
                    try{
                        Color result = Color.valueOf(color);
                        if(result.a <= 0.8f){
                            return str.substring(i + 1);
                        }
                    }catch(Exception e){
                        return str;
                    }
                }
            }
        }
        return str;
    }
}
