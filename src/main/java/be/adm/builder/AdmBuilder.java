package be.adm.builder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;

public class AdmBuilder implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("admbuilder");
	private static final long MAX_BLOCKS = 4_000_000L;

	/** Dossier commun a toutes tes maps : .minecraft/adm_batiments */
	private static Path dossier() {
		Path dir = FabricLoader.getInstance().getGameDir().resolve("adm_batiments");

		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			LOGGER.error("Impossible de creer le dossier des batiments", e);
		}

		return dir;
	}

	private static List<String> noms() {
		List<String> noms = new ArrayList<>();

		try (Stream<Path> files = Files.list(dossier())) {
			files.forEach(p -> {
				String n = p.getFileName().toString();

				if (n.endsWith(".nbt")) {
					noms.add(n.substring(0, n.length() - 4));
				}
			});
		} catch (IOException e) {
			LOGGER.error("Impossible de lister les batiments", e);
		}

		Collections.sort(noms);
		return noms;
	}

	private static String dim(Level level) {
		return level.dimension().identifier().toString();
	}

	private static boolean estBaguette(Player player, InteractionHand hand) {
		return hand == InteractionHand.MAIN_HAND && player.getItemInHand(hand).is(Items.WOODEN_AXE);
	}

	private static String coord(BlockPos p) {
		return p.getX() + " " + p.getY() + " " + p.getZ();
	}

	@Override
	public void onInitialize() {
		// 1) La baguette : hache en bois. Clic gauche = coin 1, clic droit = coin 2.
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
			if (!estBaguette(player, hand) || !(player instanceof ServerPlayer sp)) {
				return InteractionResult.PASS;
			}

			Selection.setFirst(sp.getUUID(), pos.immutable());
			sp.sendSystemMessage(Component.literal("Coin 1 : " + coord(pos)));
			return InteractionResult.FAIL;
		});

		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!estBaguette(player, hand)) {
				return InteractionResult.PASS;
			}

			if (player instanceof ServerPlayer sp) {
				BlockPos pos = hit.getBlockPos();
				Selection.setSecond(sp.getUUID(), pos.immutable());
				sp.sendSystemMessage(Component.literal("Coin 2 : " + coord(pos)));
			}

			return InteractionResult.SUCCESS;
		});

		// Messages entre l'apercu fantome (client) et le collage (monde).
		PayloadTypeRegistry.clientboundPlay().register(Payloads.Preview.TYPE, Payloads.Preview.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Payloads.Confirm.TYPE, Payloads.Confirm.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Payloads.Coin1.TYPE, Payloads.Coin1.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Payloads.Coin1.TYPE, (payload, context) -> context.server().execute(() -> {
			ServerPlayer sp = context.player();

			if (sp.getMainHandItem().is(Items.WOODEN_AXE)) {
				BlockPos pos = new BlockPos(payload.x(), payload.y(), payload.z());
				Selection.setFirst(sp.getUUID(), pos);
				sp.sendSystemMessage(Component.literal("Coin 1 : " + coord(pos)));
			}
		}));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.Confirm.TYPE, (payload, context) -> context.server().execute(() -> placer(context.player(), payload)));

		// 2) Les blocs incassables.
		ServerLifecycleEvents.SERVER_STARTED.register(Protection::load);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> Protection.save());
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> !Protection.isProtected(dim(level), pos));

		// 3) Les commandes.
		SuggestionProvider<CommandSourceStack> suggestions = (ctx, builder) -> SharedSuggestionProvider.suggest(noms(), builder);

		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> dispatcher.register(
				literal("adm")
						.executes(this::aide)
						.then(literal("aide").executes(this::aide))
						.then(literal("liste").executes(this::liste))
						.then(literal("copier").then(argument("nom", word()).executes(this::copier)))
						.then(literal("coller").then(argument("nom", word()).suggests(suggestions).executes(this::apercu)))
						.then(literal("incassable").executes(ctx -> zone(ctx, true)))
						.then(literal("cassable").executes(ctx -> zone(ctx, false)))
						.then(literal("objet").executes(this::objet))));
	}

	private int aide(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		src.sendSuccess(() -> Component.literal(
				"--- ADM Builder ---\n"
						+ "1. Prends une HACHE EN BOIS. Clic gauche sur un bloc = coin 1, clic droit sur un bloc = coin 2.\n"
						+ "2. /adm copier <nom>  : sauvegarde le batiment choisi\n"
						+ "3. Va dans une autre map, puis /adm coller <nom> : un fantome bleu apparait.\n"
						+ "   Vise un endroit, R = tourner, Entree = coller pour de bon, Retour arriere = annuler\n"
						+ "4. /adm incassable : les blocs choisis ne peuvent plus etre casses\n"
						+ "   /adm cassable : enleve la protection\n"
						+ "5. /adm objet : l'objet dans ta main devient incassable (re-tape pour annuler)\n"
						+ "/adm liste : voir les batiments sauvegardes"), false);
		return 1;
	}

	private int liste(CommandContext<CommandSourceStack> ctx) {
		List<String> noms = noms();
		ctx.getSource().sendSuccess(() -> Component.literal(noms.isEmpty() ? "Aucun batiment sauvegarde pour l'instant." : "Batiments : " + String.join(", ", noms)), false);
		return noms.size();
	}

	private int copier(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack src = ctx.getSource();
		ServerPlayer player = src.getPlayerOrException();

		if (!Selection.isComplete(player.getUUID())) {
			src.sendFailure(Component.literal("Choisis d'abord 2 coins avec la hache en bois (clic gauche = coin 1, clic droit = coin 2)."));
			return 0;
		}

		BlockPos min = Selection.min(player.getUUID());
		BlockPos max = Selection.max(player.getUUID());
		Vec3i size = new Vec3i(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1);
		long total = (long) size.getX() * size.getY() * size.getZ();

		if (total > MAX_BLOCKS) {
			src.sendFailure(Component.literal("Zone trop grande (" + total + " blocs, maximum " + MAX_BLOCKS + ")."));
			return 0;
		}

		String nom = getString(ctx, "nom");

		try {
			StructureTemplate template = new StructureTemplate();
			template.fillFromWorld(src.getLevel(), min, size, true, List.of());
			CompoundTag tag = template.save(new CompoundTag());
			NbtIo.writeCompressed(tag, dossier().resolve(nom + ".nbt"));
		} catch (IOException e) {
			LOGGER.error("Erreur a la sauvegarde", e);
			src.sendFailure(Component.literal("Erreur pendant la sauvegarde : " + e.getMessage()));
			return 0;
		}

		src.sendSuccess(() -> Component.literal("Batiment '" + nom + "' sauvegarde (" + size.getX() + "x" + size.getY() + "x" + size.getZ() + "). Tu peux le coller dans n'importe quelle map avec /adm coller " + nom), false);
		return 1;
	}

	private static StructureTemplate charger(ServerLevel level, String nom) throws IOException {
		Path path = dossier().resolve(nom + ".nbt");
		CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
		StructureTemplate template = new StructureTemplate();
		template.load(level.registryAccess().lookupOrThrow(Registries.BLOCK), tag);
		return template;
	}

	/** /adm coller nom : affiche le fantome chez le joueur (la suite se passe avec les touches). */
	private int apercu(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack src = ctx.getSource();
		ServerPlayer player = src.getPlayerOrException();
		String nom = getString(ctx, "nom");

		if (!Files.exists(dossier().resolve(nom + ".nbt"))) {
			src.sendFailure(Component.literal("Je ne trouve pas le batiment '" + nom + "'. Tape /adm liste pour voir les noms."));
			return 0;
		}

		try {
			Vec3i size = charger(src.getLevel(), nom).getSize();
			ServerPlayNetworking.send(player, new Payloads.Preview(nom, size.getX(), size.getY(), size.getZ()));
		} catch (IOException e) {
			LOGGER.error("Erreur a la lecture", e);
			src.sendFailure(Component.literal("Erreur pendant la lecture : " + e.getMessage()));
			return 0;
		}

		return 1;
	}

	/** Le joueur a appuye sur Entree : on colle pour de bon. */
	private static void placer(ServerPlayer player, Payloads.Confirm c) {
		if (!c.nom().matches("[A-Za-z0-9_.+-]+") || !Files.exists(dossier().resolve(c.nom() + ".nbt"))) {
			return;
		}

		Rotation rotation = switch (Math.floorMod(c.quarts(), 4)) {
			case 1 -> Rotation.CLOCKWISE_90;
			case 2 -> Rotation.CLOCKWISE_180;
			case 3 -> Rotation.COUNTERCLOCKWISE_90;
			default -> Rotation.NONE;
		};

		try {
			ServerLevel level = (ServerLevel) player.level();
			StructureTemplate template = charger(level, c.nom());
			StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rotation).setMirror(Mirror.NONE);

			// On decale pour que le coin le plus bas du batiment tombe exactement la ou etait le fantome.
			BoundingBox base = template.getBoundingBox(settings, BlockPos.ZERO);
			BlockPos at = new BlockPos(c.x() - base.minX(), c.y() - base.minY(), c.z() - base.minZ());
			template.placeInWorld(level, at, at, settings, level.getRandom(), 2);

			// Le batiment colle devient la selection : /adm incassable le verrouille directement.
			BoundingBox box = template.getBoundingBox(settings, at);
			Selection.setFirst(player.getUUID(), new BlockPos(box.minX(), box.minY(), box.minZ()));
			Selection.setSecond(player.getUUID(), new BlockPos(box.maxX(), box.maxY(), box.maxZ()));
			player.sendSystemMessage(Component.literal("Batiment '" + c.nom() + "' colle ! Tape /adm incassable pour le rendre incassable."));
		} catch (IOException e) {
			LOGGER.error("Erreur au collage", e);
			player.sendSystemMessage(Component.literal("Erreur pendant le collage : " + e.getMessage()));
		}
	}

	private int zone(CommandContext<CommandSourceStack> ctx, boolean incassable) throws CommandSyntaxException {
		CommandSourceStack src = ctx.getSource();
		ServerPlayer player = src.getPlayerOrException();

		if (!Selection.isComplete(player.getUUID())) {
			src.sendFailure(Component.literal("Choisis d'abord 2 coins avec la hache en bois."));
			return 0;
		}

		BlockPos min = Selection.min(player.getUUID());
		BlockPos max = Selection.max(player.getUUID());
		String dim = dim(src.getLevel());

		if (incassable) {
			Protection.add(dim, min, max);
			src.sendSuccess(() -> Component.literal("Zone rendue incassable."), false);
		} else {
			int n = Protection.remove(dim, min, max);
			src.sendSuccess(() -> Component.literal(n + " zone(s) protegee(s) retiree(s)."), false);
		}

		return 1;
	}

	private int objet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack src = ctx.getSource();
		ServerPlayer player = src.getPlayerOrException();
		ItemStack stack = player.getMainHandItem();

		if (stack.isEmpty()) {
			src.sendFailure(Component.literal("Prends un objet dans ta main d'abord."));
			return 0;
		}

		if (stack.has(DataComponents.UNBREAKABLE)) {
			stack.remove(DataComponents.UNBREAKABLE);
			src.sendSuccess(() -> Component.literal("Cet objet n'est plus incassable."), false);
		} else {
			stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
			src.sendSuccess(() -> Component.literal("Cet objet est maintenant incassable !"), false);
		}

		return 1;
	}
}
