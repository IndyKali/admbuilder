package be.adm.builder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Transforme un plan JSON (formes simples : boite, cylindre, sphere, cone, pyramide, toit_pente, arc)
 * en grille de blocs. Aucune dependance a Minecraft : les blocs sont des numeros fournis par un Resolveur.
 *
 * Axes : x = est, y = haut, z = sud. Tout est en blocs (1 bloc = 1 metre).
 */
public final class Plan {
	public static final long MAX_BLOCS = 4_000_000L;

	/** Donne le numero d'un bloc a partir de son nom ("minecraft:stone"). -1 = air. */
	public interface Resolveur {
		int id(String nomBloc) throws PlanException;
	}

	public static final class PlanException extends Exception {
		public PlanException(String message) {
			super(message);
		}
	}

	/** Resultat : grille sx*sy*sz, index = (x*sy + y)*sz + z, -1 = vide. */
	public static final class Grille {
		public int sx;
		public int sy;
		public int sz;
		public int[] ids;
		public int blocs;
		/** Position du coin 1 (laine rouge) = point de reference du collage. */
		public int refX;
		public int refY;
		public int refZ;
	}

	private interface Cellule {
		void accepter(int x, int y, int z);
	}

	private record Forme(int num, String type, JsonObject o, int ox, int oy, int oz, boolean retirer) {
	}

	private Plan() {
	}

	public static Grille generer(JsonObject racine, Resolveur resolveur, int idLaineRouge, int idLaineBleue) throws PlanException {
		JsonObject materiaux = racine.has("materiaux") && racine.get("materiaux").isJsonObject() ? racine.getAsJsonObject("materiaux") : new JsonObject();
		boolean marqueurs = !racine.has("marqueurs_laine") || racine.get("marqueurs_laine").getAsBoolean();

		if (!racine.has("formes") || !racine.get("formes").isJsonArray()) {
			throw new PlanException("Le plan doit contenir une liste \"formes\".");
		}

		List<Forme> formes = deployer(racine.getAsJsonArray("formes"));

		// Passe 1 : taille du batiment (on ne compte que les formes qui ajoutent des blocs).
		int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};

		for (Forme f : formes) {
			if (f.retirer()) {
				continue;
			}

			cellules(f, (x, y, z) -> {
				b[0] = Math.min(b[0], x);
				b[1] = Math.min(b[1], y);
				b[2] = Math.min(b[2], z);
				b[3] = Math.max(b[3], x);
				b[4] = Math.max(b[4], y);
				b[5] = Math.max(b[5], z);
			});
		}

		if (b[0] == Integer.MAX_VALUE) {
			throw new PlanException("Le plan ne construit aucun bloc (verifie les dimensions).");
		}

		int marge = marqueurs ? 1 : 0;
		long lx = (long) b[3] - b[0] + 1 + 2L * marge;
		long ly = (long) b[4] - b[1] + 1;
		long lz = (long) b[5] - b[2] + 1 + 2L * marge;

		if (lx * ly * lz > MAX_BLOCS) {
			throw new PlanException("Batiment trop grand : " + lx + " x " + ly + " x " + lz + " = " + (lx * ly * lz) + " blocs (maximum " + MAX_BLOCS + ").");
		}

		Grille g = new Grille();
		g.sx = (int) lx;
		g.sy = (int) ly;
		g.sz = (int) lz;
		g.ids = new int[g.sx * g.sy * g.sz];
		java.util.Arrays.fill(g.ids, -1);

		// Passe 2 : on pose les formes dans l'ordre (les "retirer" creusent ce qui a ete pose avant).
		final int decX = marge - b[0];
		final int decY = -b[1];
		final int decZ = marge - b[2];

		for (Forme f : formes) {
			final int id = f.retirer() ? -1 : resoudre(f, materiaux, resolveur);

			cellules(f, (x, y, z) -> {
				int gx = x + decX;
				int gy = y + decY;
				int gz = z + decZ;

				if (gx >= 0 && gx < g.sx && gy >= 0 && gy < g.sy && gz >= 0 && gz < g.sz) {
					g.ids[(gx * g.sy + gy) * g.sz + gz] = id;
				}
			});
		}

		// Les deux laines au niveau du sol, aux coins opposes (rouge = coin 1 / reference, bleue = coin 2).
		if (marqueurs) {
			g.ids[(0 * g.sy + 0) * g.sz + 0] = idLaineRouge;
			g.ids[((g.sx - 1) * g.sy + 0) * g.sz + (g.sz - 1)] = idLaineBleue;
		}

		for (int id : g.ids) {
			if (id >= 0) {
				g.blocs++;
			}
		}

		g.refX = 0;
		g.refY = 0;
		g.refZ = 0;
		return g;
	}

	// ---------------------------------------------------------------- lecture du plan

	private static int resoudre(Forme f, JsonObject materiaux, Resolveur resolveur) throws PlanException {
		if (!f.o().has("materiau")) {
			throw erreur(f, "il manque le champ \"materiau\".");
		}

		String nom = f.o().get("materiau").getAsString();

		if (materiaux.has(nom)) {
			nom = materiaux.get(nom).getAsString();
		}

		if (!nom.contains(":")) {
			nom = "minecraft:" + nom;
		}

		if (nom.equals("minecraft:air")) {
			return -1;
		}

		return resolveur.id(nom);
	}

	/** Applique "repeter" : une forme peut etre repetee sur 1, 2 ou 3 directions (fenetres, colonnes...). */
	private static List<Forme> deployer(JsonArray liste) throws PlanException {
		List<Forme> out = new ArrayList<>();
		int num = 0;

		for (JsonElement el : liste) {
			num++;

			if (!el.isJsonObject()) {
				throw new PlanException("Forme n°" + num + " : ce n'est pas un objet { ... }.");
			}

			JsonObject o = el.getAsJsonObject();

			if (!o.has("type")) {
				throw new PlanException("Forme n°" + num + " : il manque le champ \"type\".");
			}

			String type = o.get("type").getAsString();
			boolean retirer = o.has("op") && o.get("op").getAsString().equals("retirer");
			List<int[]> decalages = new ArrayList<>();
			decalages.add(new int[] {0, 0, 0});

			if (o.has("repeter")) {
				for (JsonElement r : o.getAsJsonArray("repeter")) {
					JsonObject ro = r.getAsJsonObject();
					int n = ro.has("nombre") ? ro.get("nombre").getAsInt() : 1;
					int dx = ro.has("dx") ? ro.get("dx").getAsInt() : 0;
					int dy = ro.has("dy") ? ro.get("dy").getAsInt() : 0;
					int dz = ro.has("dz") ? ro.get("dz").getAsInt() : 0;
					List<int[]> suite = new ArrayList<>();

					for (int[] d : decalages) {
						for (int k = 0; k < n; k++) {
							suite.add(new int[] {d[0] + k * dx, d[1] + k * dy, d[2] + k * dz});
						}
					}

					decalages = suite;

					if (decalages.size() > 100_000) {
						throw new PlanException("Forme n°" + num + " : trop de repetitions.");
					}
				}
			}

			for (int[] d : decalages) {
				out.add(new Forme(num, type, o, d[0], d[1], d[2], retirer));
			}
		}

		return out;
	}

	private static PlanException erreur(Forme f, String message) {
		return new PlanException("Forme n°" + f.num() + " (" + f.type() + ") : " + message);
	}

	private static int entier(Forme f, String cle) throws PlanException {
		if (!f.o().has(cle)) {
			throw erreur(f, "il manque le champ \"" + cle + "\".");
		}

		return (int) Math.round(f.o().get(cle).getAsDouble());
	}

	private static int entier(Forme f, String cle, int defaut) {
		return f.o().has(cle) ? (int) Math.round(f.o().get(cle).getAsDouble()) : defaut;
	}

	private static boolean vrai(Forme f, String cle) {
		return f.o().has(cle) && f.o().get(cle).getAsBoolean();
	}

	// ---------------------------------------------------------------- les formes

	private static void cellules(Forme f, Cellule c) throws PlanException {
		switch (f.type()) {
			case "boite" -> boite(f, c);
			case "cylindre" -> cylindre(f, c);
			case "sphere" -> sphere(f, c);
			case "cone" -> cone(f, c);
			case "pyramide" -> pyramide(f, c);
			case "toit_pente" -> toitPente(f, c);
			case "arc" -> arc(f, c);
			default -> throw erreur(f, "forme inconnue. Formes possibles : boite, cylindre, sphere, cone, pyramide, toit_pente, arc.");
		}
	}

	private static void boite(Forme f, Cellule c) throws PlanException {
		int x = entier(f, "x") + f.ox();
		int y = entier(f, "y") + f.oy();
		int z = entier(f, "z") + f.oz();
		int lx = entier(f, "largeur");
		int ly = entier(f, "hauteur");
		int lz = entier(f, "profondeur");
		boolean creux = vrai(f, "creux");
		int ep = entier(f, "epaisseur", 1);

		for (int i = 0; i < lx; i++) {
			for (int j = 0; j < ly; j++) {
				for (int k = 0; k < lz; k++) {
					if (creux) {
						int e = Math.min(Math.min(Math.min(i, lx - 1 - i), Math.min(j, ly - 1 - j)), Math.min(k, lz - 1 - k));

						if (e >= ep) {
							continue;
						}
					}

					c.accepter(x + i, y + j, z + k);
				}
			}
		}
	}

	private static void cylindre(Forme f, Cellule c) throws PlanException {
		int x = entier(f, "x") + f.ox();
		int y = entier(f, "y") + f.oy();
		int z = entier(f, "z") + f.oz();
		int r = entier(f, "rayon");
		int h = entier(f, "hauteur");
		boolean creux = vrai(f, "creux");
		int ep = entier(f, "epaisseur", 1);

		for (int i = -r; i <= r; i++) {
			for (int k = -r; k <= r; k++) {
				double d = Math.sqrt(i * i + k * k);

				if (d > r + 0.35 || (creux && d <= r - ep + 0.35)) {
					continue;
				}

				for (int j = 0; j < h; j++) {
					c.accepter(x + i, y + j, z + k);
				}
			}
		}
	}

	private static void sphere(Forme f, Cellule c) throws PlanException {
		int x = entier(f, "x") + f.ox();
		int y = entier(f, "y") + f.oy();
		int z = entier(f, "z") + f.oz();
		int r = entier(f, "rayon");
		boolean creux = vrai(f, "creux");
		int ep = entier(f, "epaisseur", 1);

		for (int i = -r; i <= r; i++) {
			for (int j = -r; j <= r; j++) {
				for (int k = -r; k <= r; k++) {
					double d = Math.sqrt(i * i + j * j + k * k);

					if (d > r + 0.35 || (creux && d <= r - ep + 0.35)) {
						continue;
					}

					c.accepter(x + i, y + j, z + k);
				}
			}
		}
	}

	private static void cone(Forme f, Cellule c) throws PlanException {
		int x = entier(f, "x") + f.ox();
		int y = entier(f, "y") + f.oy();
		int z = entier(f, "z") + f.oz();
		int r = entier(f, "rayon");
		int h = entier(f, "hauteur");

		for (int j = 0; j < h; j++) {
			double rj = r * (1.0 - (double) j / h);

			for (int i = -r; i <= r; i++) {
				for (int k = -r; k <= r; k++) {
					if (Math.sqrt(i * i + k * k) <= rj + 0.35) {
						c.accepter(x + i, y + j, z + k);
					}
				}
			}
		}
	}

	private static void pyramide(Forme f, Cellule c) throws PlanException {
		int x = entier(f, "x") + f.ox();
		int y = entier(f, "y") + f.oy();
		int z = entier(f, "z") + f.oz();
		int lx = entier(f, "largeur");
		int lz = entier(f, "profondeur");
		int h = entier(f, "hauteur");
		double cx = (lx - 1) / 2.0;
		double cz = (lz - 1) / 2.0;

		for (int j = 0; j < h; j++) {
			double reste = 1.0 - (double) j / h;
			double hx = Math.max(0.51, lx / 2.0 * reste);
			double hz = Math.max(0.51, lz / 2.0 * reste);

			for (int i = 0; i < lx; i++) {
				for (int k = 0; k < lz; k++) {
					if (Math.abs(i - cx) < hx && Math.abs(k - cz) < hz) {
						c.accepter(x + i, y + j, z + k);
					}
				}
			}
		}
	}

	/** Toit a deux pentes en escalier. "axe" = direction du faite (x ou z). */
	private static void toitPente(Forme f, Cellule c) throws PlanException {
		int x = entier(f, "x") + f.ox();
		int y = entier(f, "y") + f.oy();
		int z = entier(f, "z") + f.oz();
		int lx = entier(f, "largeur");
		int lz = entier(f, "profondeur");
		int h = entier(f, "hauteur");
		boolean faiteSurX = !f.o().has("axe") || f.o().get("axe").getAsString().equals("x");
		int longueur = faiteSurX ? lx : lz;
		int travers = faiteSurX ? lz : lx;

		for (int j = 0; j < h; j++) {
			int recul = (int) Math.floor(j * (travers / 2.0) / h);
			int a = recul;
			int b = travers - 1 - recul;

			if (a > b) {
				break;
			}

			for (int l = 0; l < longueur; l++) {
				for (int t = a; t <= b; t++) {
					if (faiteSurX) {
						c.accepter(x + l, y + j, z + t);
					} else {
						c.accepter(x + t, y + j, z + l);
					}
				}
			}
		}
	}

	/** Portion d'anneau (mur courbe) : angles en degres, 0 = est, 90 = sud. rayon_int = rayon_ext donne un mur de 1 bloc. */
	private static void arc(Forme f, Cellule c) throws PlanException {
		int cx = entier(f, "cx") + f.ox();
		int cz = entier(f, "cz") + f.oz();
		int y = entier(f, "y") + f.oy();
		int ri = entier(f, "rayon_int");
		int re = entier(f, "rayon_ext");
		int h = entier(f, "hauteur");
		double debut = entier(f, "angle_debut", 0);
		double fin = entier(f, "angle_fin", 360);
		double ouverture = fin - debut;

		for (int i = -re - 1; i <= re + 1; i++) {
			for (int k = -re - 1; k <= re + 1; k++) {
				double d = Math.sqrt(i * i + k * k);

				if (d < ri - 0.5 || d >= re + 0.5) {
					continue;
				}

				if (ouverture < 360) {
					double a = Math.toDegrees(Math.atan2(k, i));
					double rel = ((a - debut) % 360 + 360) % 360;

					if (rel > ouverture) {
						continue;
					}
				}

				for (int j = 0; j < h; j++) {
					c.accepter(cx + i, y + j, cz + k);
				}
			}
		}
	}
}
