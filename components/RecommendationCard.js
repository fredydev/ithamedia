export default function RecommendationCard({ name, platform, description, verified, logoUrl }) {
  return (
    <div className="bg-white shadow-md rounded-lg p-4 w-64 min-w-[16rem] flex flex-col gap-4">
      {/* Image ou Avatar Placeholder */}
      <div className="w-16 h-16 rounded-full mx-auto overflow-hidden">
        {logoUrl ? (
          <img
            src={logoUrl}
            alt={`Logo de ${name}`}
            className="w-full h-full object-cover"
          />
        ) : (
          <div className="w-full h-full bg-gray-200 flex items-center justify-center text-gray-500 text-sm">
            No Image
          </div>
        )}
      </div>

      {/* Titre et plateforme */}
      <div className="text-center">
        <h3 className="text-lg font-semibold text-gray-800">{name}</h3>
        <p className="text-sm text-gray-500">{platform}</p>
      </div>

      {/* Description */}
      <p className="text-sm text-gray-600 text-center">{description}</p>

      {/* Contenu vérifié */}
      {verified && (
        <div className="flex items-center justify-center gap-2 text-green-500 mt-2">
          <span className="w-4 h-4 bg-green-500 rounded-full flex items-center justify-center text-white text-xs font-bold">
            ✔
          </span>
          <span className="text-sm font-medium">Contenu vérifié</span>
        </div>
      )}
    </div>
  );
}

  