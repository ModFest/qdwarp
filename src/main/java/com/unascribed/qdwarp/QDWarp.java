package com.unascribed.qdwarp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import com.google.common.base.Splitter;

import it.unimi.dsi.fastutil.doubles.DoubleUnaryOperator;
import it.unimi.dsi.fastutil.floats.FloatUnaryOperator;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;

public class QDWarp implements ModInitializer {

	private static final Logger log = LoggerFactory.getLogger(QDWarp.class);
	
	private static final Map<String, WarpPos> WARPS = Collections.synchronizedMap(new LinkedHashMap<>());

	private static final Splitter COMMA_SPLITTER = Splitter.on(',');
	
	public record NamedWarp(String name, WarpPos pos) {
		
		public static NamedWarp fromString(String str) {
			int idx = str.lastIndexOf('=');
			if (idx == -1) return null;
			return new NamedWarp(str.substring(0, idx), WarpPos.fromString(str.substring(idx+1)));
		}
		
	}
	
	public record WarpPos(RegistryKey<World> dimension, double x, double y, double z, float yaw, float pitch) {
		
		@Override
		public String toString() {
			return dimension.getValue().toString()+","+x+","+y+","+z+","+yaw+","+pitch;
		}
		
		public static WarpPos fromString(String str) {
			var iter = COMMA_SPLITTER.split(str).iterator();
			Identifier id = new Identifier(iter.next());
			double x = Double.parseDouble(iter.next());
			double y = Double.parseDouble(iter.next());
			double z = Double.parseDouble(iter.next());
			float yaw = Float.parseFloat(iter.next());
			float pitch = Float.parseFloat(iter.next());
			assert !iter.hasNext();
			return new WarpPos(RegistryKey.of(Registry.WORLD_KEY, id), x, y, z, yaw, pitch);
		}
		
	}
	
	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTING.register((server) -> {
			WARPS.clear();
			try (Stream<String> s = Files.lines(server.getSavePath(WorldSavePath.ROOT).resolve("warps.ini"))) {
				s
					.filter(str -> !str.startsWith(";"))
					.map(NamedWarp::fromString)
					.forEach(nw -> WARPS.put(nw.name(), nw.pos()));
			} catch (NoSuchFileException ignore) {
			} catch (IOException e) {
				log.error("Error while loading warps", e);
			}
			log.info("Loaded {} warps", WARPS.size());
		});
		CommandRegistrationCallback.EVENT.register((dis, cctx, env) -> {
			SuggestionProvider<ServerCommandSource> warps = (ctx, builder) -> {
				String input = builder.getInput().substring(builder.getStart());
				WARPS.keySet().stream()
					.filter(s -> s.contains(input))
					.forEach(builder::suggest);
				return builder.buildFuture();
			};
			dis.register(CommandManager.literal("warp")
					.then(CommandManager.argument("name", StringArgumentType.greedyString())
							.suggests(warps)
							.executes((ctx) -> {
								var src = ctx.getSource();
								warp(src, src.getPlayer(), StringArgumentType.getString(ctx, "name"));
								return 0;
							})
						)
					);
			dis.register(CommandManager.literal("warpother")
					.requires(src -> src.hasPermissionLevel(2))
					.then(CommandManager.argument("player", EntityArgumentType.player())
						.then(CommandManager.argument("name", StringArgumentType.greedyString())
								.suggests(warps)
								.executes((ctx) -> {
									var src = ctx.getSource();
									var player = EntityArgumentType.getPlayer(ctx, "player");
									String name = StringArgumentType.getString(ctx, "name");
									warp(src, player, name);
									src.sendFeedback(Text.literal("Warped ")
											.append(player.getDisplayName())
											.append(" to ")
											.append(name), true);
									return 0;
								})
							)
						)
					);
			dis.register(CommandManager.literal("rmwarp")
					.requires(src -> src.hasPermissionLevel(4))
					.then(CommandManager.argument("name", StringArgumentType.greedyString())
							.suggests(warps)
							.executes((ctx) -> {
								var src = ctx.getSource();
								var name = StringArgumentType.getString(ctx, "name");
								WarpPos pos = WARPS.remove(name);
								if (pos == null) {
									src.sendError(Text.literal("That warp doesn't exist"));
									return 0;
								}
								src.sendFeedback(Text.literal("Deleted warp "+name), true);
								save(src.getServer());
								return 0;
							})
						)
					);
			var node = CommandManager.literal("mkwarp")
					.requires(src -> src.hasPermissionLevel(4));
			record PosChoice(String name, DoubleUnaryOperator op) {}
			record RotChoice(String name, FloatUnaryOperator op) {}
			PosChoice[] posChoices = {
					new PosChoice("exact", c -> c),
					new PosChoice("block-corner", c -> ((int)c*4)/4D),
					new PosChoice("block-center", c -> ((int)c)+0.5)
				};
			RotChoice[] rotChoices = {
					new RotChoice("exact", f -> f),
					new RotChoice("45", f -> ((int)f/45)*45f),
					new RotChoice("cardinal", f -> ((int)f/90)*90f)
			};
			for (var pos : posChoices) {
				var posNode = CommandManager.literal("pos-"+pos.name());
				for (var rot : rotChoices) {
					DoubleUnaryOperator posOp = pos.op();
					FloatUnaryOperator rotOp = rot.op();
					posNode.then(CommandManager.literal("rot-"+rot.name())
							.then(CommandManager.argument("name", StringArgumentType.greedyString())
								.executes((ctx) -> {
									var src = ctx.getSource();
									String name = StringArgumentType.getString(ctx, "name");
									if (WARPS.containsKey(name)) {
										src.sendError(Text.literal("That name is taken, use ")
												.append(Text.literal("/rmwarp")
														.styled(style -> style.withUnderline(true)
																.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/rmwarp "+name))
																.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("/rmwarp "+name))))
												.append(Text.literal(" first if you want to replace it")
														.styled(style -> style.withUnderline(false)))));
										return 0;
									}
									var key = src.getWorld().getRegistryKey();
									var pos1 = src.getPosition();
									double x = posOp.apply(pos1.x);
									double y = pos1.y;
									double z = posOp.apply(pos1.z);
									var ent = Optional.ofNullable(src.getEntity());
									float yaw = ent.map(Entity::getYaw).map(rotOp).orElse(0F);
									float pitch = ent.map(Entity::getPitch).map(rotOp).orElse(0F);
									src.sendFeedback(Text.literal("Created warp "+name), true);
									WARPS.put(name, new WarpPos(key, x, y, z, yaw, pitch));
									save(src.getServer());
									return 0;
								})
							)
						);
				}
				node.then(posNode);
			}
			dis.register(node);
		});
	}

	private void warp(ServerCommandSource src, ServerPlayerEntity player, String name) {
		WarpPos pos = WARPS.get(name);
		if (pos == null) {
			src.sendError(Text.literal("That warp doesn't exist"));
			return;
		}
		ServerWorld world = src.getServer().getWorld(pos.dimension());
		if (world == null) {
			src.sendError(Text.literal("That warp refers to a nonexistent dimension"));
			return;
		}
		player.teleport(world, pos.x(), pos.y(), pos.z(), pos.yaw(), pos.pitch());
	}

	private void save(MinecraftServer server) {
		try {
			Files.write(server.getSavePath(WorldSavePath.ROOT).resolve("warps.ini"),
				WARPS.entrySet().stream()
					.map(en -> en.getKey()+"="+en.getValue())
					.toList());
		} catch (IOException e) {
			log.warn("Failed to save warps", e);
		}
	}
	

}
