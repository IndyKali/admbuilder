package be.adm.builder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Liste des zones incassables, sauvegardee dans le dossier de la map (adm_incassable.txt). */
public final class Protection {
	private record Zone(String dim, int x1, int y1, int z1, int x2, int y2, int z2) {
		boolean contains(String d, BlockPos p) {
			return dim.equals(d) && p.getX() >= x1 && p.getX() <= x2 && p.getY() >= y1 && p.getY() <= y2 && p.getZ() >= z1 && p.getZ() <= z2;
		}

		boolean intersects(String d, BlockPos a, BlockPos b) {
			return dim.equals(d) && a.getX() <= x2 && b.getX() >= x1 && a.getY() <= y2 && b.getY() >= y1 && a.getZ() <= z2 && b.getZ() >= z1;
		}
	}

	private static final List<Zone> ZONES = new ArrayList<>();
	private static Path file;

	private Protection() {
	}

	public static void load(MinecraftServer server) {
		ZONES.clear();
		file = server.getWorldPath(LevelResource.ROOT).resolve("adm_incassable.txt");

		if (!Files.exists(file)) {
			return;
		}

		try {
			for (String line : Files.readAllLines(file)) {
				String[] p = line.trim().split(" ");

				if (p.length == 7) {
					ZONES.add(new Zone(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseInt(p[6])));
				}
			}
		} catch (IOException | NumberFormatException e) {
			AdmBuilder.LOGGER.error("Impossible de lire les zones incassables", e);
		}
	}

	public static void save() {
		if (file == null) {
			return;
		}

		List<String> lines = new ArrayList<>();

		for (Zone z : ZONES) {
			lines.add(z.dim() + " " + z.x1() + " " + z.y1() + " " + z.z1() + " " + z.x2() + " " + z.y2() + " " + z.z2());
		}

		try {
			Files.write(file, lines);
		} catch (IOException e) {
			AdmBuilder.LOGGER.error("Impossible de sauvegarder les zones incassables", e);
		}
	}

	public static void add(String dim, BlockPos min, BlockPos max) {
		ZONES.add(new Zone(dim, min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ()));
		save();
	}

	/** Retire toutes les zones qui touchent la selection. Renvoie le nombre de zones retirees. */
	public static int remove(String dim, BlockPos min, BlockPos max) {
		int before = ZONES.size();
		ZONES.removeIf(z -> z.intersects(dim, min, max));
		save();
		return before - ZONES.size();
	}

	public static boolean isProtected(String dim, BlockPos pos) {
		for (Zone z : ZONES) {
			if (z.contains(dim, pos)) {
				return true;
			}
		}

		return false;
	}
}
