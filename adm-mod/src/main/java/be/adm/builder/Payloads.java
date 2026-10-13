package be.adm.builder;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Les deux messages echanges entre le jeu (apercu fantome) et le monde (collage). */
public final class Payloads {
	private Payloads() {
	}

	/** Serveur -> client : "affiche le fantome de ce batiment (cette taille)". */
	public record Preview(String nom, int sx, int sy, int sz) implements CustomPacketPayload {
		public static final Type<Preview> TYPE = new Type<>(Identifier.fromNamespaceAndPath("admbuilder", "preview"));
		public static final StreamCodec<FriendlyByteBuf, Preview> CODEC = StreamCodec.ofMember(Preview::write, Preview::read);

		private static Preview read(FriendlyByteBuf buf) {
			return new Preview(buf.readUtf(), buf.readInt(), buf.readInt(), buf.readInt());
		}

		private void write(FriendlyByteBuf buf) {
			buf.writeUtf(nom);
			buf.writeInt(sx);
			buf.writeInt(sy);
			buf.writeInt(sz);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> serveur : "colle ce batiment ici, avec cette rotation". */
	public record Confirm(String nom, int x, int y, int z, int quarts) implements CustomPacketPayload {
		public static final Type<Confirm> TYPE = new Type<>(Identifier.fromNamespaceAndPath("admbuilder", "confirm"));
		public static final StreamCodec<FriendlyByteBuf, Confirm> CODEC = StreamCodec.ofMember(Confirm::write, Confirm::read);

		private static Confirm read(FriendlyByteBuf buf) {
			return new Confirm(buf.readUtf(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt());
		}

		private void write(FriendlyByteBuf buf) {
			buf.writeUtf(nom);
			buf.writeInt(x);
			buf.writeInt(y);
			buf.writeInt(z);
			buf.writeInt(quarts);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> serveur : "le joueur a clique gauche sur ce bloc avec la hache" (coin 1). */
	public record Coin1(int x, int y, int z) implements CustomPacketPayload {
		public static final Type<Coin1> TYPE = new Type<>(Identifier.fromNamespaceAndPath("admbuilder", "coin1"));
		public static final StreamCodec<FriendlyByteBuf, Coin1> CODEC = StreamCodec.ofMember(Coin1::write, Coin1::read);

		private static Coin1 read(FriendlyByteBuf buf) {
			return new Coin1(buf.readInt(), buf.readInt(), buf.readInt());
		}

		private void write(FriendlyByteBuf buf) {
			buf.writeInt(x);
			buf.writeInt(y);
			buf.writeInt(z);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
