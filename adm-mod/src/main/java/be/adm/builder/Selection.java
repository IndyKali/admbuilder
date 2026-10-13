package be.adm.builder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;

/** Retient les deux coins choisis par chaque joueur avec la hache en bois. */
public final class Selection {
	private static final Map<UUID, BlockPos[]> SELECTIONS = new HashMap<>();

	private Selection() {
	}

	public static BlockPos[] get(UUID id) {
		return SELECTIONS.computeIfAbsent(id, k -> new BlockPos[2]);
	}

	public static void setFirst(UUID id, BlockPos pos) {
		get(id)[0] = pos;
	}

	public static void setSecond(UUID id, BlockPos pos) {
		get(id)[1] = pos;
	}

	public static boolean isComplete(UUID id) {
		BlockPos[] s = get(id);
		return s[0] != null && s[1] != null;
	}

	public static BlockPos min(UUID id) {
		BlockPos[] s = get(id);
		return new BlockPos(Math.min(s[0].getX(), s[1].getX()), Math.min(s[0].getY(), s[1].getY()), Math.min(s[0].getZ(), s[1].getZ()));
	}

	public static BlockPos max(UUID id) {
		BlockPos[] s = get(id);
		return new BlockPos(Math.max(s[0].getX(), s[1].getX()), Math.max(s[0].getY(), s[1].getY()), Math.max(s[0].getZ(), s[1].getZ()));
	}
}
