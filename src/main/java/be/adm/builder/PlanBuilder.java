package be.adm.builder;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Pont entre les plans JSON (dossier .minecraft/adm_plans) et le reste du mod :
 * il ecrit les memes fichiers qu'un /adm copier (.blocs et .apercu dans adm_batiments),
 * donc l'apercu bleu, la rotation et le collage fonctionnent tels quels.
 */
public final class PlanBuilder {
	private PlanBuilder() {
	}

	public static Path dossierPlans() {
		Path dir = FabricLoader.getInstance().getGameDir().resolve("adm_plans");

		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			AdmBuilder.LOGGER.error("Impossible de creer le dossier des plans", e);
		}

		return dir;
	}

	public static List<String> noms() {
		List<String> noms = new ArrayList<>();

		try (Stream<Path> files = Files.list(dossierPlans())) {
			files.forEach(p -> {
				String n = p.getFileName().toString();

				if (n.endsWith(".json")) {
					noms.add(n.substring(0, n.length() - 5));
				}
			});
		} catch (IOException e) {
			AdmBuilder.LOGGER.error("Impossible de lister les plans", e);
		}

		Collections.sort(noms);
		return noms;
	}

	/** Lit le plan, genere le batiment et ecrit ses fichiers dans le dossier des batiments. */
	public static Plan.Grille construire(ServerLevel level, Path dossierBatiments, String nom) throws IOException, Plan.PlanException {
		Path fichier = dossierPlans().resolve(nom + ".json");

		if (!Files.exists(fichier)) {
			throw new Plan.PlanException("Je ne trouve pas " + fichier + " (mets ton fichier de plan dans le dossier adm_plans).");
		}

		Plan.Grille g;

		try {
			JsonObject racine = JsonParser.parseString(Files.readString(fichier)).getAsJsonObject();
			int rouge = Block.BLOCK_STATE_REGISTRY.getId(Blocks.RED_WOOL.defaultBlockState());
			int bleu = Block.BLOCK_STATE_REGISTRY.getId(Blocks.BLUE_WOOL.defaultBlockState());
			g = Plan.generer(racine, PlanBuilder::idDuBloc, rouge, bleu);
		} catch (Plan.PlanException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new Plan.PlanException("Plan illisible (JSON mal forme ou champ du mauvais type) : " + e.getMessage());
		}

		// Un ancien .nbt du meme nom serait colle a la place du plan : on le retire.
		Files.deleteIfExists(dossierBatiments.resolve(nom + ".nbt"));
		ecrire(level, dossierBatiments, nom, g);
		return g;
	}

	private static int idDuBloc(String nomBloc) throws Plan.PlanException {
		Identifier id = Identifier.tryParse(nomBloc);

		if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
			throw new Plan.PlanException("Bloc inconnu : " + nomBloc);
		}

		BlockState etat = BuiltInRegistries.BLOCK.getValue(id).defaultBlockState();
		return Block.BLOCK_STATE_REGISTRY.getId(etat);
	}

	/** Meme format que ecrireApercu() de AdmBuilder : .blocs (tous les blocs) et .apercu (surface + couleurs). */
	private static void ecrire(ServerLevel level, Path dossier, String nom, Plan.Grille g) throws IOException {
		int sx = g.sx;
		int sy = g.sy;
		int sz = g.sz;
		Map<Integer, Integer> couleurs = new HashMap<>();
		int[][] voisins = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
		boolean[] surface = new boolean[g.ids.length];
		int total = 0;

		for (int x = 0; x < sx; x++) {
			for (int y = 0; y < sy; y++) {
				for (int z = 0; z < sz; z++) {
					int i = (x * sy + y) * sz + z;

					if (g.ids[i] < 0) {
						continue;
					}

					for (int[] v : voisins) {
						int nx = x + v[0];
						int ny = y + v[1];
						int nz = z + v[2];

						if (nx < 0 || ny < 0 || nz < 0 || nx >= sx || ny >= sy || nz >= sz || g.ids[(nx * sy + ny) * sz + nz] < 0) {
							surface[i] = true;
							total++;
							break;
						}
					}
				}
			}
		}

		try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(dossier.resolve(nom + ".blocs"))))) {
			out.writeInt(-2);
			out.writeInt(sx);
			out.writeInt(sy);
			out.writeInt(sz);
			out.writeInt(g.refX);
			out.writeInt(g.refY);
			out.writeInt(g.refZ);

			for (int id : g.ids) {
				out.writeInt(id);
			}
		}

		try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(dossier.resolve(nom + ".apercu"))))) {
			out.writeInt(-2);
			out.writeInt(sx);
			out.writeInt(sy);
			out.writeInt(sz);
			out.writeInt(g.refX);
			out.writeInt(g.refY);
			out.writeInt(g.refZ);
			out.writeInt(total);

			for (int x = 0; x < sx; x++) {
				for (int y = 0; y < sy; y++) {
					for (int z = 0; z < sz; z++) {
						int i = (x * sy + y) * sz + z;

						if (surface[i]) {
							int id = g.ids[i];
							int col = couleurs.computeIfAbsent(id, k -> {
								BlockState etat = Block.BLOCK_STATE_REGISTRY.byId(k);
								int c = etat == null ? 0 : etat.getMapColor(level, BlockPos.ZERO).col;
								return c == 0 ? 0xA0A0A0 : c;
							});
							out.writeInt(x);
							out.writeInt(y);
							out.writeInt(z);
							out.writeInt(col);
						}
					}
				}
			}
		}
	}
}
