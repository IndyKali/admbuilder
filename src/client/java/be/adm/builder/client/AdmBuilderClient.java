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
	private static BlockPos cible = null; // le bloc ou se posera le coin 1 (= ce que tu vises), null si tu ne vises rien

	// Les blocs du fantome (positions dans le batiment d'origine + couleur). null = on dessine juste une boite.
	private static int[] bx;
	private static int[] by;
	private static int[] bz;
	private static int[] bc;

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
			cible = null;
			chargerApercu(nom);
			arme = true;
			context.client().gui.hud.setOverlayMessage(Component.literal("Prends l'epee en bois : vise un endroit, R = tourner de 45 degres, clic droit = coller, clic gauche = effacer"), false);
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
				cible = null;
				return;
			}

			if (tourne) {
				quarts = (quarts + 1) % 8; // un pas = 45 degres
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
		cible = null;
		client.gui.hud.setOverlayMessage(Component.literal("Fantome efface."), false);
	}

	/** Le bloc vise (au-dessus de la face visee) est l'endroit ou se pose le coin 1 du batiment. */
	private static void cibler(Minecraft client) {
		if (client.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
			cible = hit.getBlockPos().relative(hit.getDirection());
		} else {
			cible = null;
		}
	}

	private static void coller() {
		if (arme && epee && cible != null) {
			ClientPlayNetworking.send(new Payloads.Confirm(nom, cible.getX(), cible.getY(), cible.getZ(), quarts));
		}
	}

	private static void dessiner(LevelRenderContext context) {
		if (!arme || !epee || cible == null) {
			return;
		}

		Vec3 camera = context.levelState().cameraRenderState.pos;
		PoseStack pose = context.poseStack();
		pose.pushPose();
		pose.translate(-camera.x, -camera.y, -camera.z);

		// Le coin 1 se pose sur le centre du bloc vise ; tout le fantome tourne autour de ce point.
		final float px = cible.getX() + 0.5f;
		final float pz = cible.getZ() + 0.5f;
		final float py = cible.getY();
		final float cos = (float) Math.cos(quarts * Math.PI / 4.0);
		final float sin = (float) Math.sin(quarts * Math.PI / 4.0);

		context.submitNodeCollector().submitCustomGeometry(pose, RenderTypes.debugFilledBox(), (pp, buffer) -> {
			float gauche = -refX - 0.5f;
			float haut = -refZ - 0.5f;
			float droite = sx - refX - 0.5f;
			float bas = sz - refZ - 0.5f;
			float y0 = py - refY;

			// Un contour tres leger pour voir la taille totale, et une dalle cyan pour voir le sol.
			boite(pp, buffer, px, pz, cos, sin, gauche, haut, droite, bas, y0, y0 + sy, ARGB.colorFromFloat(bx == null ? 0.4f : 0.18f, 0.2f, 0.7f, 1.0f));
			boite(pp, buffer, px, pz, cos, sin, gauche, haut, droite, bas, y0, y0 + 0.12f, ARGB.colorFromFloat(0.60f, 0.0f, 0.9f, 1.0f));

			if (bx != null) {
				for (int i = 0; i < bx.length; i++) {
					int rgb = bc[i];
					// Bleu fantome : la couleur du bloc d'origine ne sert qu'a garder un peu de relief.
					float lum = (0.299f * ((rgb >> 16) & 255) + 0.587f * ((rgb >> 8) & 255) + 0.114f * (rgb & 255)) / 255f;
					int couleur = ARGB.colorFromFloat(0.80f, 0.15f + 0.25f * lum, 0.55f + 0.35f * lum, 1.0f);
					float dx = bx[i] - refX;
					float dz = bz[i] - refZ;
					float oy = py + (by[i] - refY);
					boite(pp, buffer, px, pz, cos, sin, dx - 0.49f, dz - 0.49f, dx + 0.49f, dz + 0.49f, oy + 0.01f, oy + 0.99f, couleur);
				}
			}

			// Le coin 1 (point de reference) en jaune + un poteau, pour voir ou le batiment sera pose.
			int jaune = ARGB.colorFromFloat(0.95f, 1.0f, 0.85f, 0.1f);
			boite(pp, buffer, px, pz, cos, sin, -0.5f, -0.5f, 0.5f, 0.5f, py, py + 1f, jaune);
			boite(pp, buffer, px, pz, cos, sin, -0.15f, -0.15f, 0.15f, 0.15f, py + 1f, py + 6f, jaune);
		});

		pose.popPose();
	}

	/** Une boite pleine, tournee autour du point (px, pz). (x0,z0)-(x1,z1) sont mesures a partir de ce point. */
	private static void boite(PoseStack.Pose pose, VertexConsumer v, float px, float pz, float cos, float sin, float x0, float z0, float x1, float z1, float y0, float y1, int c) {
		float ax = px + x0 * cos - z0 * sin;
		float az = pz + x0 * sin + z0 * cos;
		float bx2 = px + x1 * cos - z0 * sin;
		float bz2 = pz + x1 * sin + z0 * cos;
		float cx = px + x1 * cos - z1 * sin;
		float cz = pz + x1 * sin + z1 * cos;
		float dx = px + x0 * cos - z1 * sin;
		float dz = pz + x0 * sin + z1 * cos;

		// Avant (A-B)
		v.addVertex(pose, ax, y0, az).setColor(c);
		v.addVertex(pose, bx2, y0, bz2).setColor(c);
		v.addVertex(pose, bx2, y1, bz2).setColor(c);
		v.addVertex(pose, ax, y1, az).setColor(c);
		// Arriere (C-D)
		v.addVertex(pose, cx, y0, cz).setColor(c);
		v.addVertex(pose, dx, y0, dz).setColor(c);
		v.addVertex(pose, dx, y1, dz).setColor(c);
		v.addVertex(pose, cx, y1, cz).setColor(c);
		// Gauche (D-A)
		v.addVertex(pose, dx, y0, dz).setColor(c);
		v.addVertex(pose, ax, y0, az).setColor(c);
		v.addVertex(pose, ax, y1, az).setColor(c);
		v.addVertex(pose, dx, y1, dz).setColor(c);
		// Droite (B-C)
		v.addVertex(pose, bx2, y0, bz2).setColor(c);
		v.addVertex(pose, cx, y0, cz).setColor(c);
		v.addVertex(pose, cx, y1, cz).setColor(c);
		v.addVertex(pose, bx2, y1, bz2).setColor(c);
		// Haut
		v.addVertex(pose, ax, y1, az).setColor(c);
		v.addVertex(pose, bx2, y1, bz2).setColor(c);
		v.addVertex(pose, cx, y1, cz).setColor(c);
		v.addVertex(pose, dx, y1, dz).setColor(c);
		// Bas
		v.addVertex(pose, dx, y0, dz).setColor(c);
		v.addVertex(pose, cx, y0, cz).setColor(c);
		v.addVertex(pose, bx2, y0, bz2).setColor(c);
		v.addVertex(pose, ax, y0, az).setColor(c);
	}
}
