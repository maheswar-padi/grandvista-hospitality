import type { Room } from '../../lib/api/types';
import { RoomIllustration } from './RoomIllustration';

/** First uploaded photograph if there is one, otherwise the illustrative artwork. */
export function RoomVisual({ room, className }: { room: Room; className?: string }) {
  const image = room.images[0];
  if (image) {
    return (
      <img
        src={image.url}
        alt={image.altText ?? `Photograph of ${room.name}`}
        loading="lazy"
        decoding="async"
        className={`${className ?? ''} object-cover`}
      />
    );
  }
  return (
    <RoomIllustration
      typeCode={room.roomType.code}
      view={room.viewType}
      className={className}
      label={`Illustration of the ${room.name} (artwork, not a photograph)`}
    />
  );
}
