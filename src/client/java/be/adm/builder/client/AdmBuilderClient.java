package be.adm.builder.client;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.lwjgl.sdl.SDLScancode;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;

import be.adm.builder.Payloads;

/** Le fantome bleu : avec l'epee en bois en main, le batiment choisi suit ton regard. R = tourner, clic droit = coller, clic gauche = effacer. */
public class AdmBuilderClient implements ClientModInitializer {
	private static final int MAX_BLOCS_DESSINES = 25_000;

	private static boolean arme = false; // un batiment est choisi (fantome disponible)
	private static boolean epee = false; // le joueur tient l'epee en bois
	private static String nom = "";
	private static int refX = 0; // coin 1 dans le batiment d'origine (point de reference)
	private static int refY = 0;
	private static int refZ = 0;
	private static int sx;
	private static int sy;
	private static int sz;
	private static int quarts = 0;
	private static BlockPos coin = null; // coin le plus bas du fantome (= le bloc vise), null si tu ne vises rien

	// Les blocs du fantome (positions dans le batiment d'origine + couleur). null = on dessine juste une boite.
	private static int[] bx;
	private static int[] by;
	private static int[] bz;
	private static int[] bc;

	private static int largeur() {
		return quarts % 2 == 0 ? sx : sz;
	}

	private static int profondeur() {
		return quarts % 2 == 0 ? sz : sx;
	}

	private static int tourneX(int x, int z) {
		return switch (quarts) {
			case 1 -> (sz - 1) - z;
			case 2 -> (sx - 1) - x;
			case 3 -> z;
			default -> x;
		};
	}

	private static int tourneZ(int x, int z) {
		return switch (quarts) {
			case 1 -> x;
			case 2 -> (sz - 1) - z;
			case 3 -> (sx - 1) - x;
			default -> z;
		};
	}

	private static void chargerApercu(String nomBatiment) {
		refX = 0;
		refY = 0;
		refZ = 0;
		bx = null;
		by = null;
		bz = null;
		bc = null;
		Path fichier = FabricLoader.getInstance().getGameDir().resolve("adm_batiments").resolve(nomBatiment + ".apercu");

		if (!Files.exists(fichier)) {
			return;
		}

		try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(fichier)))) {
			if (in.readInt() != -2) {
				return; // ancien fichier sans point de reference : on affichera juste une boite
			}

			in.readInt();
			in.readInt();
			in.readInt();
			refX = in.readInt();
			refY = in.readInt();
			refZ = in.readInt();
			int total = in.readInt();
			int pas = Math.max(1, (total + MAX_BLOCS_DESSINES - 1) / MAX_BLOCS_DESSINES);
			int garde = (total + pas - 1) / pas;
			int[] x = new int[garde];
			int[] y = new int[garde];
			int[] z = new int[garde];
			int[] c = new int[garde];
			int k = 0;

			for (int i = 0; i < total; i++) {
				int px = in.readInt();
				int py = in.readInt();
				int pz = in.readInt();
				int pc = in.readInt();

				if (i % pas == 0 && k < garde) {
					x[k] = px;
					y[k] = py;
					z[k] = pz;
					c[k] = pc;
					k++;
				}
			}

			bx = x;
			by = y;
			bz = z;
			bc = c;
		} catch (IOException e) {
			bx = null;
		}
	}

	@Override
	public void onInitializeClient() {
		KeyMapping.Category categorie = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("admbuilder", "main"));
		KeyMapping tourner = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.admbuilder.tourner", InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_R, categorie));
		KeyMapping confirmer = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.admbuilder.confirmer", InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_RETURN, categorie));
		KeyMapping annuler = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.admbuilder.annuler", InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_X, categorie));

		// Le monde nous dit : "voila le batiment choisi".
		ClientPlayNetworking.registerGlobalReceiver(Payloads.Preview.TYPE, (payload, context) -> context.client().execute(() -> {
			nom = payload.nom();
			sx = payload.sx();
			sy = payload.sy();
			sz = payload.sz();
			quarts = 0;
			coin = null;
			chargerApercu(nom);
			arme = true;
			context.client().gui.hud.setOverlayMessage(Component.literal("Prends l'epee en bois : vise un endroit, R = tourner, clic droit = coller, clic gauche = effacer"), false);
		}));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean tourne = false;
			boolean valide = false;
			boolean annule = false;

			while (tourner.consumeClick()) {
				tourne = true;
			}

			while (confirmer.consumeClick()) {
				valide = true;
			}

			while (annuler.consumeClick()) {
				annule = true;
			}

			if (client.player == null) {
				arme = false;
				epee = false;
				return;
			}

			epee = client.player.getMainHandItem().is(Items.WOODEN_SWORD);

			if (!arme) {
				return;
			}

			if (annule) {
				effacer(client);
				return;
			}

			if (!epee) {
				coin = null;
				return;
			}

			if (tourne) {
				quarts = (quarts + 1) % 4;
			}

			cibler(client);

			if (valide) {
				coller();
			}
		});

		// Clic gauche avec la hache : on previent le monde (coin 1) et on n'abime pas le bloc.
		// Clic gauche avec l'epee (quand un fantome est la) : on efface le fantome.
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
			if (!level.isClientSide() || hand != InteractionHand.MAIN_HAND) {
				return InteractionResult.PASS;
			}

			if (player.getItemInHand(hand).is(Items.WOODEN_AXE)) {
				ClientPlayNetworking.send(new Payloads.Coin1(pos.getX(), pos.getY(), pos.getZ()));
				return InteractionResult.FAIL;
			}

			if (arme && player.getItemInHand(hand).is(Items.WOODEN_SWORD)) {
				effacer(Minecraft.getInstance());
				return InteractionResult.FAIL;
			}

			return InteractionResult.PASS;
		});

		// Clic droit avec l'epee (quand un fantome est la) : on colle.
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() && arme && hand == InteractionHand.MAIN_HAND && player.getItemInHand(hand).is(Items.WOODEN_SWORD)) {
				coller();
				return InteractionResult.FAIL;
			}

			return InteractionResult.PASS;
		});

		LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register(AdmBuilderClient::dessiner);
	}

	private static void effacer(Minecraft client) {
		arme = false;
		coin = null;
		client.gui.hud.setOverlayMessage(Component.literal("Fantome efface."), false);
	}

	/** Le bloc vise (au-dessus de la face visee) est l'endroit ou se pose le coin 1 du batiment. */
	private static void cibler(Minecraft client) {
		if (client.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
			BlockPos cible = hit.getBlockPos().relative(hit.getDirection());
			coin = new BlockPos(cible.getX() - tourneX(refX, refZ), cible.getY() - refY, cible.getZ() - tourneZ(refX, refZ));
		} else {
			coin = null;
		}
	}

	private static void coller() {
		if (arme && epee && coin != null) {
			ClientPlayNetworking.send(new Payloads.Confirm(nom, coin.getX(), coin.getY(), coin.getZ(), quarts));
		}
	}

	private static void dessiner(LevelRenderContext context) {
		if (!arme || !epee || coin == null) {
			return;
		}

		Vec3 camera = context.levelState().cameraRenderState.pos;
		PoseStack pose = context.poseStack();
		pose.pushPose();
		pose.translate(-camera.x, -camera.y, -camera.z);

		final int cx = coin.getX();
		final int cy = coin.getY();
		final int cz = coin.getZ();
		final int l = largeur();
		final int p = profondeur();

		context.submitNodeCollector().submitCustomGeometry(pose, RenderTypes.debugFilledBox(), (pp, buffer) -> {
			if (bx == null) {
				// Pas de fichier apercu (batiment copie avec l'ancienne version) : une boite bleue.
				boite(pp, buffer, cx, cy, cz, cx + l, cy + sy, cz + p, ARGB.colorFromFloat(0.4f, 0.2f, 0.7f, 1.0f));
				return;
			}

			// Un contour tres leger pour voir la taille totale.
			boite(pp, buffer, cx, cy, cz, cx + l, cy + sy, cz + p, ARGB.colorFromFloat(0.18f, 0.2f, 0.7f, 1.0f));

			// Le sol du batiment : une dalle cyan bien visible pour voir l'emplacement exact.
			boite(pp, buffer, cx, cy, cz, cx + l, cy + 0.12f, cz + p, ARGB.colorFromFloat(0.60f, 0.0f, 0.9f, 1.0f));

			for (int i = 0; i < bx.length; i++) {
				int x = bx[i];
				int z = bz[i];
				int rgb = bc[i];
				// Bleu fantome : la couleur du bloc d'origine ne sert qu'a garder un peu de relief.
				float lum = (0.299f * ((rgb >> 16) & 255) + 0.587f * ((rgb >> 8) & 255) + 0.114f * (rgb & 255)) / 255f;
				int couleur = ARGB.colorFromFloat(0.80f, 0.15f + 0.25f * lum, 0.55f + 0.35f * lum, 1.0f);
				float ox = cx + tourneX(x, z);
				float oy = cy + by[i];
				float oz = cz + tourneZ(x, z);
				boite(pp, buffer, ox + 0.01f, oy + 0.01f, oz + 0.01f, ox + 0.99f, oy + 0.99f, oz + 0.99f, couleur);
			}

			// Le coin 1 (point de reference) en jaune, pour voir ou le batiment sera pose.
			float jx = cx + tourneX(refX, refZ);
			float jy = cy + refY;
			float jz = cz + tourneZ(refX, refZ);
			boite(pp, buffer, jx, jy, jz, jx + 1f, jy + 1f, jz + 1f, ARGB.colorFromFloat(0.95f, 1.0f, 0.85f, 0.1f));
			// Un poteau jaune au-dessus du coin 1, visible de loin.
			boite(pp, buffer, jx + 0.35f, jy + 1f, jz + 0.35f, jx + 0.65f, jy + 6f, jz + 0.65f, ARGB.colorFromFloat(0.85f, 1.0f, 0.85f, 0.1f));
		});

		pose.popPose();
	}

	private static void boite(PoseStack.Pose pose, VertexConsumer v, float x0, float y0, float z0, float x1, float y1, float z1, int c) {
		// Avant
		v.addVertex(pose, x0, y0, z0).setColor(c);
		v.addVertex(pose, x1, y0, z0).setColor(c);
		v.addVertex(pose, x1, y1, z0).setColor(c);
		v.addVertex(pose, x0, y1, z0).setColor(c);
		// Arriere
		v.addVertex(pose, x1, y0, z1).setColor(c);
		v.addVertex(pose, x0, y0, z1).setColor(c);
		v.addVertex(pose, x0, y1, z1).setColor(c);
		v.addVertex(pose, x1, y1, z1).setColor(c);
		// Gauche
		v.addVertex(pose, x0, y0, z1).setColor(c);
		v.addVertex(pose, x0, y0, z0).setColor(c);
		v.addVertex(pose, x0, y1, z0).setColor(c);
		v.addVertex(pose, x0, y1, z1).setColor(c);
		// Droite
		v.addVertex(pose, x1, y0, z0).setColor(c);
		v.addVertex(pose, x1, y0, z1).setColor(c);
		v.addVertex(pose, x1, y1, z1).setColor(c);
		v.addVertex(pose, x1, y1, z0).setColor(c);
		// Haut
		v.addVertex(pose, x0, y1, z0).setColor(c);
		v.addVertex(pose, x1, y1, z0).setColor(c);
		v.addVertex(pose, x1, y1, z1).setColor(c);
		v.addVertex(pose, x0, y1, z1).setColor(c);
		// Bas
		v.addVertex(pose, x0, y0, z1).setColor(c);
		v.addVertex(pose, x1, y0, z1).setColor(c);
		v.addVertex(pose, x1, y0, z0).setColor(c);
		v.addVertex(pose, x0, y0, z0).setColor(c);
	}
}
