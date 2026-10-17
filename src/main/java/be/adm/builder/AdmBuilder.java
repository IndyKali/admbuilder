package be.adm.builder;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
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
						+ "COPIER (hache en bois) : clic gauche = coin 1 (point de reference), clic droit = coin 2, puis /adm copier <nom>\n"
						+ "COLLER (epee en bois) : le fantome bleu apparait, vise un endroit = le coin 1 se pose la.\n"
						+ "   R = tourner de 45 degres - clic droit (ou Entree) = coller - clic gauche (ou X) = effacer le fantome\n"
						+ "   /adm coller <nom> = choisir un autre batiment sauvegarde, /adm liste = les voir\n"
						+ "BLOQUER : /adm incassable (zone choisie), /adm cassable (enleve), /adm objet (objet en main)"), false);
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
			BlockPos coin1 = Selection.get(player.getUUID())[0];
			ecrireApercu(src.getLevel(), min, size, nom, coin1.getX() - min.getX(), coin1.getY() - min.getY(), coin1.getZ() - min.getZ());
			ServerPlayNetworking.send(player, new Payloads.Preview(nom, size.getX(), size.getY(), size.getZ()));
		} catch (IOException e) {
			LOGGER.error("Erreur a la sauvegarde", e);
			src.sendFailure(Component.literal("Erreur pendant la sauvegarde : " + e.getMessage()));
			return 0;
		}

		src.sendSuccess(() -> Component.literal("Batiment '" + nom + "' sauvegarde (" + size.getX() + "x" + size.getY() + "x" + size.getZ() + "). Prends l'epee en bois pour voir le fantome. Dans une autre map : /adm coller " + nom), false);
		return 1;
	}

	/** Fichier "apercu" : la liste des blocs visibles du batiment avec leur couleur, pour le fantome. */
	private static void ecrireApercu(ServerLevel level, BlockPos min, Vec3i size, String nom, int refX, int refY, int refZ) throws IOException {
		int sx = size.getX();
		int sy = size.getY();
		int sz = size.getZ();
		boolean[] plein = new boolean[sx * sy * sz];
		int[] couleur = new int[sx * sy * sz];
		int[] ids = new int[sx * sy * sz];
		java.util.Arrays.fill(ids, -1);
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

		for (int x = 0; x < sx; x++) {
			for (int y = 0; y < sy; y++) {
				for (int z = 0; z < sz; z++) {
					p.set(min.getX() + x, min.getY() + y, min.getZ() + z);
					BlockState etat = level.getBlockState(p);

					if (!etat.isAir()) {
						int i = (x * sy + y) * sz + z;
						int col = etat.getMapColor(level, p).col;
						plein[i] = true;
						couleur[i] = col == 0 ? 0xA0A0A0 : col;
						ids[i] = Block.BLOCK_STATE_REGISTRY.getId(etat);
					}
				}
			}
		}

		// On ne garde que les blocs qui touchent l'air (la surface), c'est plus leger.
		int[][] voisins = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
		int total = 0;
		boolean[] surface = new boolean[plein.length];

		for (int x = 0; x < sx; x++) {
			for (int y = 0; y < sy; y++) {
				for (int z = 0; z < sz; z++) {
					int i = (x * sy + y) * sz + z;

					if (!plein[i]) {
						continue;
					}

					for (int[] v : voisins) {
						int nx = x + v[0];
						int ny = y + v[1];
						int nz = z + v[2];

						if (nx < 0 || ny < 0 || nz < 0 || nx >= sx || ny >= sy || nz >= sz || !plein[(nx * sy + ny) * sz + nz]) {
							surface[i] = true;
							total++;
							break;
						}
					}
				}
			}
		}

		// Tous les blocs (pour le collage en diagonale a 45 degres).
		try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(dossier().resolve(nom + ".blocs"))))) {
			out.writeInt(-2);
			out.writeInt(sx);
			out.writeInt(sy);
			out.writeInt(sz);
			out.writeInt(refX);
			out.writeInt(refY);
			out.writeInt(refZ);

			for (int id : ids) {
				out.writeInt(id);
			}
		}

		try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(dossier().resolve(nom + ".apercu"))))) {
			out.writeInt(-2); // marqueur de version du fichier
			out.writeInt(sx);
			out.writeInt(sy);
			out.writeInt(sz);
			out.writeInt(refX);
			out.writeInt(refY);
			out.writeInt(refZ);
			out.writeInt(total);

			for (int x = 0; x < sx; x++) {
				for (int y = 0; y < sy; y++) {
					for (int z = 0; z < sz; z++) {
						int i = (x * sy + y) * sz + z;

						if (surface[i]) {
							out.writeInt(x);
							out.writeInt(y);
							out.writeInt(z);
							out.writeInt(couleur[i]);
						}
					}
				}
			}
		}
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

	private record Entete(int sx, int sy, int sz, int refX, int refY, int refZ) {
	}

	/** Taille du batiment et position du coin 1 (point de reference), lus dans le fichier apercu. */
	private static Entete lireEntete(String nom) {
		Path f = dossier().resolve(nom + ".apercu");

		if (!Files.exists(f)) {
			return null;
		}

		try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(f)))) {
			if (in.readInt() != -2) {
				return null;
			}

			return new Entete(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
		} catch (IOException e) {
			return null;
		}
	}

	// Rotation d'un point du batiment par quarts de tour (comme Minecraft), dans le cadre du batiment.
	private static int tourneX(int q, int x, int z, int sx, int sz) {
		return switch (q) {
			case 1 -> (sz - 1) - z;
			case 2 -> (sx - 1) - x;
			case 3 -> z;
			default -> x;
		};
	}

	private static int tourneZ(int q, int x, int z, int sx, int sz) {
		return switch (q) {
			case 1 -> x;
			case 2 -> (sz - 1) - z;
			case 3 -> (sx - 1) - x;
			default -> z;
		};
	}

	private static Rotation rotationDe(int quarts) {
		return switch (Math.floorMod(quarts, 4)) {
			case 1 -> Rotation.CLOCKWISE_90;
			case 2 -> Rotation.CLOCKWISE_180;
			case 3 -> Rotation.COUNTERCLOCKWISE_90;
			default -> Rotation.NONE;
		};
	}

	/** Le joueur a clique avec l'epee : on colle pour de bon. x/y/z = la ou se pose le coin 1, quarts = nombre de pas de 45 degres. */
	private static void placer(ServerPlayer player, Payloads.Confirm c) {
		if (!c.nom().matches("[A-Za-z0-9_.+-]+") || !Files.exists(dossier().resolve(c.nom() + ".nbt"))) {
			return;
		}

		int etapes = Math.floorMod(c.quarts(), 8);
		Entete e = lireEntete(c.nom());
		ServerLevel level = (ServerLevel) player.level();
		BlockPos cible = new BlockPos(c.x(), c.y(), c.z());

		if (etapes % 2 == 1) {
			placerDiagonale(player, level, c.nom(), cible, etapes, e);
			return;
		}

		int q = etapes / 2;
		Rotation rotation = rotationDe(q);

		try {
			StructureTemplate template = charger(level, c.nom());
			StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rotation).setMirror(Mirror.NONE);

			// Le coin le plus bas du batiment = la cible moins la position (tournee) du coin 1.
			BlockPos min = e == null ? cible : new BlockPos(cible.getX() - tourneX(q, e.refX(), e.refZ(), e.sx(), e.sz()), cible.getY() - e.refY(), cible.getZ() - tourneZ(q, e.refX(), e.refZ(), e.sx(), e.sz()));
			BoundingBox base = template.getBoundingBox(settings, BlockPos.ZERO);
			BlockPos at = new BlockPos(min.getX() - base.minX(), min.getY() - base.minY(), min.getZ() - base.minZ());

			// On remplace vraiment : tout ce qui etait dans la zone disparait avant le collage.
			BoundingBox zone = template.getBoundingBox(settings, at);
			BlockPos.MutableBlockPos c2 = new BlockPos.MutableBlockPos();

			for (int x = zone.minX(); x <= zone.maxX(); x++) {
				for (int y = zone.minY(); y <= zone.maxY(); y++) {
					for (int z = zone.minZ(); z <= zone.maxZ(); z++) {
						c2.set(x, y, z);

						if (!level.getBlockState(c2).isAir()) {
							level.setBlock(c2, Blocks.AIR.defaultBlockState(), 34);
						}
					}
				}
			}

			template.placeInWorld(level, at, at, settings, level.getRandom(), 2);

			// Le batiment colle devient la selection : /adm incassable le verrouille directement.
			BoundingBox box = template.getBoundingBox(settings, at);
			Selection.setFirst(player.getUUID(), new BlockPos(box.minX(), box.minY(), box.minZ()));
			Selection.setSecond(player.getUUID(), new BlockPos(box.maxX(), box.maxY(), box.maxZ()));
			player.sendSystemMessage(Component.literal("Batiment '" + c.nom() + "' colle ! Tape /adm incassable pour le rendre incassable."));
		} catch (IOException ex) {
			LOGGER.error("Erreur au collage", ex);
			player.sendSystemMessage(Component.literal("Erreur pendant le collage : " + ex.getMessage()));
		}
	}

	/** Collage a 45 degres (experimental) : chaque case de destination va chercher le bloc qui lui correspond dans le batiment d'origine. */
	private static void placerDiagonale(ServerPlayer player, ServerLevel level, String nom, BlockPos cible, int etapes, Entete e) {
		if (e == null || !Files.exists(dossier().resolve(nom + ".blocs"))) {
			player.sendSystemMessage(Component.literal("Ce batiment a ete copie avec une ancienne version : recopie-le avec /adm copier pour pouvoir le tourner a 45 degres."));
			return;
		}

		int sx = e.sx();
		int sy = e.sy();
		int sz = e.sz();
		int[] ids = new int[sx * sy * sz];

		try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(dossier().resolve(nom + ".blocs"))))) {
			if (in.readInt() != -2) {
				player.sendSystemMessage(Component.literal("Fichier du batiment illisible : recopie-le avec /adm copier."));
				return;
			}

			for (int i = 0; i < 6; i++) {
				in.readInt();
			}

			for (int i = 0; i < ids.length; i++) {
				ids[i] = in.readInt();
			}
		} catch (IOException ex) {
			LOGGER.error("Erreur a la lecture des blocs", ex);
			player.sendSystemMessage(Component.literal("Erreur pendant la lecture : " + ex.getMessage()));
			return;
		}

		double phi = etapes * Math.PI / 4.0;
		double cos = Math.cos(phi);
		double sin = Math.sin(phi);
		Rotation rot = rotationDe((int) (Math.round(etapes / 2.0) % 4));

		double rayonMax = 0;

		for (double ax : new double[] {-e.refX() - 0.5, sx - e.refX() - 0.5}) {
			for (double az : new double[] {-e.refZ() - 0.5, sz - e.refZ() - 0.5}) {
				rayonMax = Math.max(rayonMax, Math.hypot(ax, az));
			}
		}

		int rayon = (int) Math.ceil(rayonMax) + 1;
		// On regarde le centre de la case, puis ses 4 coins : ca evite les trous dans les murs fins.
		double[][] points = {{0, 0}, {0.45, 0.45}, {0.45, -0.45}, {-0.45, 0.45}, {-0.45, -0.45}};
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int[] colX = new int[points.length];
		int[] colZ = new int[points.length];
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		int posees = 0;

		for (int dx = -rayon; dx <= rayon; dx++) {
			for (int dz = -rayon; dz <= rayon; dz++) {
				int n = 0;
				boolean centreDedans = false;

				for (int j = 0; j < points.length; j++) {
					double[] pt = points[j];
					double ox = dx + pt[0];
					double oz = dz + pt[1];
					int xs = e.refX() + (int) Math.round(ox * cos + oz * sin);
					int zs = e.refZ() + (int) Math.round(-ox * sin + oz * cos);

					if (xs >= 0 && xs < sx && zs >= 0 && zs < sz) {
						colX[n] = xs;
						colZ[n] = zs;
						n++;

						if (j == 0) {
							centreDedans = true;
						}
					}
				}

				for (int y = 0; y < sy; y++) {
					BlockState choisi = null;

					for (int k = 0; k < n && choisi == null; k++) {
						int id = ids[(colX[k] * sy + y) * sz + colZ[k]];

						if (id >= 0) {
							BlockState etat = Block.BLOCK_STATE_REGISTRY.byId(id);

							if (etat != null) {
								choisi = etat.rotate(rot);
							}
						}
					}

					if (choisi == null && !centreDedans) {
						continue;
					}

					p.set(cible.getX() + dx, cible.getY() - e.refY() + y, cible.getZ() + dz);

					if (choisi != null) {
						level.setBlock(p, choisi, 34);
						posees++;
					} else if (!level.getBlockState(p).isAir()) {
						// Case vide du batiment : on enleve ce qui etait la avant (remplacement).
						level.setBlock(p, Blocks.AIR.defaultBlockState(), 34);
					}

					minX = Math.min(minX, p.getX());
					minY = Math.min(minY, p.getY());
					minZ = Math.min(minZ, p.getZ());
					maxX = Math.max(maxX, p.getX());
					maxY = Math.max(maxY, p.getY());
					maxZ = Math.max(maxZ, p.getZ());
				}
			}
		}

		if (posees > 0) {
			Selection.setFirst(player.getUUID(), new BlockPos(minX, minY, minZ));
			Selection.setSecond(player.getUUID(), new BlockPos(maxX, maxY, maxZ));
		}

		player.sendSystemMessage(Component.literal("Batiment '" + nom + "' colle en diagonale (45 degres, experimental : les coffres, panneaux etc. ne sont pas gardes). Tape /adm incassable pour le verrouiller."));
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
