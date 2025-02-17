package shadows.gateways.gate;

import java.text.DecimalFormat;
import java.util.Optional;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion.BlockInteraction;
import net.minecraftforge.registries.ForgeRegistries;
import shadows.gateways.Gateways;
import shadows.gateways.codec.GatewayCodecs;
import shadows.gateways.entity.GatewayEntity;
import shadows.gateways.entity.GatewayEntity.FailureReason;
import shadows.placebo.codec.PlaceboCodecs.CodecProvider;
import shadows.placebo.json.NBTAdapter;

/**
 * A Failure is a negative effect that triggers when a gateway errors for some reason.
 */
public interface Failure extends CodecProvider<Failure> {

	public static final BiMap<ResourceLocation, Codec<? extends Failure>> CODECS = HashBiMap.create();

	public static final Codec<Failure> CODEC = GatewayCodecs.mapBacked("Gateway Failure", CODECS);

	/**
	 * Called when this failure is to be applied.
	 * @param level The level the gateway is in
	 * @param gate The gateway entity
	 * @param summoner The summoning player
	 * @param reason The reason the failure happened
	 */
	public void onFailure(ServerLevel level, GatewayEntity gate, Player summoner, FailureReason reason);

	public void appendHoverText(Consumer<Component> list);

	public static void initSerializers() {
		register("explosion", ExplosionFailure.CODEC);
		register("mob_effect", MobEffectFailure.CODEC);
		register("summon", SummonFailure.CODEC);
		register("chanced", ChancedFailure.CODEC);
		register("command", CommandFailure.CODEC);
	}

	private static void register(String id, Codec<? extends Failure> codec) {
		CODECS.put(Gateways.loc(id), codec);
	}

	/**
	 * Triggers an explosion on failure, with a specific strength, and optional fire/block damage.
	 */
	public static record ExplosionFailure(float strength, boolean fire, boolean blockDamage) implements Failure {

		//Formatter::off
		public static Codec<ExplosionFailure> CODEC = RecordCodecBuilder.create(inst -> inst
			.group(
				Codec.FLOAT.fieldOf("strength").forGetter(ExplosionFailure::strength),
				Codec.BOOL.fieldOf("fire").forGetter(ExplosionFailure::fire),
				Codec.BOOL.fieldOf("block_damage").forGetter(ExplosionFailure::blockDamage))
				.apply(inst, ExplosionFailure::new)
			);
		//Formatter::on

		@Override
		public void onFailure(ServerLevel level, GatewayEntity gate, Player summoner, FailureReason reason) {
			level.explode(gate, gate.getX(), gate.getY(), gate.getZ(), strength, fire, blockDamage ? BlockInteraction.DESTROY : BlockInteraction.NONE);
		}

		@Override
		public Codec<? extends Failure> getCodec() {
			return CODEC;
		}

		@Override
		public void appendHoverText(Consumer<Component> list) {
			list.accept(Component.translatable("failure.gateways.explosion", this.strength, this.fire, this.blockDamage));
		}
	}

	/**
	 * Applies a mob effect to all nearby players on failure.
	 */
	public static record MobEffectFailure(MobEffect effect, int duration, int amplifier) implements Failure {

		//Formatter::off
		public static Codec<MobEffectFailure> CODEC = RecordCodecBuilder.create(inst -> inst
			.group(
				ForgeRegistries.MOB_EFFECTS.getCodec().fieldOf("effect").forGetter(MobEffectFailure::effect),
				Codec.INT.fieldOf("duration").forGetter(MobEffectFailure::duration),
				Codec.INT.fieldOf("amplifier").forGetter(MobEffectFailure::amplifier))
				.apply(inst, MobEffectFailure::new)
			);
		//Formatter::on

		@Override
		public void onFailure(ServerLevel level, GatewayEntity gate, Player summoner, FailureReason reason) {
			level.getNearbyPlayers(TargetingConditions.forNonCombat(), null, gate.getBoundingBox().inflate(gate.getGateway().leashRange)).forEach(p -> {
				p.addEffect(new MobEffectInstance(effect, duration, amplifier));
			});
		}

		@Override
		public Codec<? extends Failure> getCodec() {
			return CODEC;
		}

		@Override
		public void appendHoverText(Consumer<Component> list) {
			list.accept(Component.translatable("failure.gateways.mob_effect", toComponent(new MobEffectInstance(effect, duration, amplifier))));
		}

		private static Component toComponent(MobEffectInstance mobeffectinstance) {
			MutableComponent mutablecomponent = Component.translatable(mobeffectinstance.getDescriptionId());
			MobEffect mobeffect = mobeffectinstance.getEffect();

			if (mobeffectinstance.getAmplifier() > 0) {
				mutablecomponent = Component.translatable("potion.withAmplifier", mutablecomponent, Component.translatable("potion.potency." + mobeffectinstance.getAmplifier()));
			}

			if (mobeffectinstance.getDuration() > 20) {
				mutablecomponent = Component.translatable("potion.withDuration", mutablecomponent, MobEffectUtil.formatDuration(mobeffectinstance, 1));
			}

			return mutablecomponent.withStyle(mobeffect.getCategory().getTooltipFormatting());
		}
	}

	/**
	 * Summons a specific entity on failure.
	 */
	public static record SummonFailure(EntityType<?> type, @Nullable CompoundTag nbt, int count) implements Failure {

		//Formatter::off
		public static Codec<SummonFailure> CODEC = RecordCodecBuilder.create(inst -> inst
			.group(
				ForgeRegistries.ENTITY_TYPES.getCodec().fieldOf("entity").forGetter(SummonFailure::type),
				NBTAdapter.EITHER_CODEC.optionalFieldOf("nbt").forGetter(f -> Optional.ofNullable(f.nbt)),
				Codec.INT.fieldOf("count").forGetter(SummonFailure::count))
				.apply(inst, (type, nbt, count) -> new SummonFailure(type, nbt.orElse(null), count))
			);
		//Formatter::on

		@Override
		public void onFailure(ServerLevel level, GatewayEntity gate, Player summoner, FailureReason reason) {
			for (int i = 0; i < count; i++) {
				Entity entity = type.create(level);
				if (nbt != null) entity.load(nbt);
				entity.moveTo(gate.getX(), gate.getY(), gate.getZ(), 0, 0);
				level.addFreshEntity(entity);
			}
		}

		@Override
		public Codec<? extends Failure> getCodec() {
			return CODEC;
		}

		@Override
		public void appendHoverText(Consumer<Component> list) {
			list.accept(Component.translatable("failure.gateways.summon", count, Component.translatable(type.getDescriptionId())));
		}
	}

	/**
	 * Wraps a failure with a random chance applied to it.
	 */
	public static record ChancedFailure(Failure failure, float chance) implements Failure {

		//Formatter::off
		public static Codec<ChancedFailure> CODEC = RecordCodecBuilder.create(inst -> inst
			.group(
				Failure.CODEC.fieldOf("failure").forGetter(ChancedFailure::failure),
				Codec.FLOAT.fieldOf("chance").forGetter(ChancedFailure::chance))
				.apply(inst, ChancedFailure::new)
			);
		//Formatter::on

		@Override
		public void onFailure(ServerLevel level, GatewayEntity gate, Player summoner, FailureReason reason) {
			if (level.random.nextFloat() < chance) failure.onFailure(level, gate, summoner, reason);
		}

		@Override
		public Codec<? extends Failure> getCodec() {
			return CODEC;
		}

		static DecimalFormat fmt = new DecimalFormat("##.##%");

		@Override
		public void appendHoverText(Consumer<Component> list) {
			this.failure.appendHoverText(c -> {
				list.accept(Component.translatable("failure.gateways.chance", fmt.format(chance * 100), c));
			});
		}
	}

	/**
	 * Executes a command on Gateway failure.
	 */
	public static record CommandFailure(String command, String desc) implements Failure {

		//Formatter::off
		public static Codec<CommandFailure> CODEC = RecordCodecBuilder.create(inst -> inst
			.group(
				Codec.STRING.fieldOf("command").forGetter(CommandFailure::command),
				Codec.STRING.fieldOf("desc").forGetter(CommandFailure::desc))
				.apply(inst, CommandFailure::new)
			);
		//Formatter::on

		@Override
		public void onFailure(ServerLevel level, GatewayEntity gate, Player summoner, FailureReason reason) {
			String realCmd = command.replace("<summoner>", summoner.getGameProfile().getName());
			level.getServer().getCommands().performPrefixedCommand(gate.createCommandSourceStack(), realCmd);
		}

		@Override
		public Codec<? extends Failure> getCodec() {
			return CODEC;
		}

		@Override
		public void appendHoverText(Consumer<Component> list) {
			list.accept(Component.translatable(desc));
		}
	}

}