package be.adm.builder.client;

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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import be.adm.builder.Payloads;

/** L'apercu fantome : une boite bleue qui suit ton regard. R = tourner, Entree = coller, Retour arriere = annuler. */
public class AdmBuilderClient implements ClientModInitializer {
	private static boolean actif = false;
	private static String nom = "";
	private static int sx;
	private static int sy;
	private static int sz;
	private static int quarts = 0;
	private static BlockPos coin = null; // coin le plus bas du fantome, null si tu ne vises rien

	private static int largeur() {
		return quarts % 2 == 0 ? sx : sz;
	}

	private static int profondeur() {
		return quarts % 2 == 0 ? sz : sx;
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
			actif = true;
			context.client().gui.hud.setOverlayMessage(Component.literal("Vise un endroit - R : tourner - Entree : coller - Retour arriere : annuler"), false);
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

			// Le fantome se pose sur le bloc que tu vises, centre sur ce point.
			if (client.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
				BlockPos cible = hit.getBlockPos().relative(hit.getDirection());
				coin = new BlockPos(cible.getX() - largeur() / 2, cible.getY(), cible.getZ() - profondeur() / 2);
			} else {
				coin = null;
			}

			if (valide && coin != null) {
				ClientPlayNetworking.send(new Payloads.Confirm(nom, coin.getX(), coin.getY(), coin.getZ(), quarts));
				actif = false;
			}
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

		AABB boite = new AABB(coin.getX(), coin.getY(), coin.getZ(), coin.getX() + largeur(), coin.getY() + sy, coin.getZ() + profondeur());
		int couleur = ARGB.colorFromFloat(0.4f, 0.2f, 0.7f, 1.0f);
		context.submitNodeCollector().submitCustomGeometry(pose, RenderTypes.debugFilledBox(), (p, buffer) -> boiteRemplie(p, buffer, boite, couleur));

		pose.popPose();
	}

	private static void boiteRemplie(PoseStack.Pose pose, VertexConsumer v, AABB b, int c) {
		// Avant
		v.addVertex(pose, (float) b.minX, (float) b.minY, (float) b.minZ).setColor(c);
		v.addVertex(pose, (float) b.maxX, (float) b.minY, (float) b.minZ).setColor(c);
		v.addVertex(pose, (float) b.maxX, (float) b.maxY, (float) b.minZ).setColor(c);
		v.addVertex(pose, (float) b.minX, (float) b.maxY, (float) b.minZ).setColor(c);
		// Arriere
		v.addVertex(pose, (float) b.maxX, (float) b.minY, (float) b.maxZ).setColor(c);
		v.addVertex(pose, (float) b.minX, (float) b.minY, (float) b.maxZ).setColor(c);
		v.addVertex(pose, (float) b.minX, (float) b.maxY, (float) b.maxZ).setColor(c);
		v.addVertex(pose, (float) b.maxX, (float) b.maxY, (float) b.maxZ).setColor(c);
		// Gauche
		v.addVertex(pose, (float) b.minX, (float) b.minY, (float) b.maxZ).setColor(c);
		v.addVertex(pose, (float) b.minX, (float) b.minY, (float) b.minZ).setColor(c);
		v.addVertex(pose, (float) b.minX, (float) b.maxY, (float) b.minZ).setColor(c);
		v.addVertex(pose, (float) b.minX, (float) b.maxY, (float) b.maxZ).setColor(c);
		// Droite
		v.addVertex(pose, (float) b.maxX, (float) b.minY, (float) b.minZ).setColor(c);
		v.addVertex(pose, (float) b.maxX, (float) b.minY, (float) b.maxZ).setColor(c);
		v.addVertex(pose, (float) b.maxX, (float) b.maxY, (float) b.maxZ).setColor(c);
		v.addVertex(pose, (float) b.maxX, (float) b.maxY, (float) b.minZ).setColor(c);
		// Haut
		v.addVertex(pose, (float) b.minX, (float) b.maxY, (float) b.minZ).setColor(c);
		v.addVertex(pose, (float) b.maxX, (float) b.maxY, (float) b.minZ).setColor(c);
		v.addVertex(pose, (float) b.maxX, (float) b.maxY, (float) b.maxZ).setColor(c);
		v.addVertex(pose, (float) b.minX, (float) b.maxY, (float) b.maxZ).setColor(c);
		// Bas
		v.addVertex(pose, (float) b.minX, (float) b.minY, (float) b.maxZ).setColor(c);
		v.addVertex(pose, (float) b.maxX, (float) b.minY, (float) b.maxZ).setColor(c);
		v.addVertex(pose, (float) b.maxX, (float) b.minY, (float) b.minZ).setColor(c);
		v.addVertex(pose, (float) b.minX, (float) b.minY, (float) b.minZ).setColor(c);
	}
}
