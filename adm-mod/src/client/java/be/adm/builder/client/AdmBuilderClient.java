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
import net.fabricmc.loader.api.FabricLoader;

import be.adm.builder.Payloads;

/** Le fantome : les blocs du batiment en transparence, qui suivent ton regard. R = tourner, Entree = coller, Retour arriere = annuler. */
public class AdmBuilderClient implements ClientModInitializer {
	private static final int MAX_BLOCS_DESSINES = 25_000;

	private static boolean actif = false;
	private static String nom = "";
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

	private static void chargerApercu(String nomBatiment) {
		bx = null;
		by = null;
		bz = null;
		bc = null;
		Path fichier = FabricLoader.getInstance().getGameDir().resolve("adm_batiments").resolve(nomBatiment + ".apercu");

		if (!Files.exists(fichier)) {
			return;
		}

		try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(fichier)))) {
			in.readInt();
			in.readInt();
			in.readInt();
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
		KeyMapping annuler = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.admbuilder.annuler", InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_BACKSPACE, categorie));

		// Le monde nous dit : "affiche ce batiment".
		ClientPlayNetworking.registerGlobalReceiver(Payloads.Preview.TYPE, (payload, context) -> context.client().execute(() -> {
			nom = payload.nom();
			sx = payload.sx();
			sy = payload.sy();
			sz = payload.sz();
			quarts = 0;
			coin = null;
			chargerApercu(nom);
			actif = true;
			context.client().gui.hud.setOverlayMessage(Component.literal("Vise un endroit (= coin du batiment) - R : tourner - Entree : coller - Retour arriere : annuler"), false);
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

			if (client.player == null || !actif) {
				return;
			}

			if (annule) {
				actif = false;
				client.gui.hud.setOverlayMessage(Component.literal("Collage annule."), false);
				return;
			}

			if (tourne) {
				quarts = (quarts + 1) % 4;
			}

			// Le bloc que tu vises (au-dessus de la face visee) devient le coin du batiment.
			if (client.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
				coin = hit.getBlockPos().relative(hit.getDirection());
			} else {
				coin = null;
			}

			if (valide && coin != null) {
				ClientPlayNetworking.send(new Payloads.Confirm(nom, coin.getX(), coin.getY(), coin.getZ(), quarts));
				actif = false;
			}
		});

		// Clic gauche avec la hache : on previent le monde (coin 1) et on n'abime pas le bloc.
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
			if (level.isClientSide() && hand == InteractionHand.MAIN_HAND && player.getItemInHand(hand).is(Items.WOODEN_AXE)) {
				ClientPlayNetworking.send(new Payloads.Coin1(pos.getX(), pos.getY(), pos.getZ()));
				return InteractionResult.FAIL;
			}

			return InteractionResult.PASS;
		});

		LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register(AdmBuilderClient::dessiner);
	}

	private static void dessiner(LevelRenderContext context) {
		if (!actif || coin == null) {
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
			boite(pp, buffer, cx, cy, cz, cx + l, cy + sy, cz + p, ARGB.colorFromFloat(0.12f, 0.2f, 0.7f, 1.0f));

			for (int i = 0; i < bx.length; i++) {
				int x = bx[i];
				int z = bz[i];
				int rx;
				int rz;

				switch (quarts) {
					case 1 -> {
						rx = (sz - 1) - z;
						rz = x;
					}
					case 2 -> {
						rx = (sx - 1) - x;
						rz = (sz - 1) - z;
					}
					case 3 -> {
						rx = z;
						rz = (sx - 1) - x;
					}
					default -> {
						rx = x;
						rz = z;
					}
				}

				int rgb = bc[i];
				int couleur = ARGB.colorFromFloat(0.6f, ((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f);
				float ox = cx + rx;
				float oy = cy + by[i];
				float oz = cz + rz;
				boite(pp, buffer, ox + 0.04f, oy + 0.04f, oz + 0.04f, ox + 0.96f, oy + 0.96f, oz + 0.96f, couleur);
			}
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
