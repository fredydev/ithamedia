export default function SearchAndFilter() {
  return (
    <div className="container mx-auto p-4 md:hidden">
      <div className="flex flex-col gap-4">
        {/* Barre de recherche */}
        <input
          type="text"
          placeholder="Rechercher un compte ou une chaîne..."
          className="w-full border rounded-lg px-4 py-2"
        />

        {/* Filtre par pays */}
        <select
          id="country"
          className="border rounded-lg px-4 py-2 text-gray-600"
        >
          <option value="all">Tous les pays</option>
          <option value="france">France</option>
          <option value="usa">États-Unis</option>
          <option value="canada">Canada</option>
        </select>
      </div>
    </div>
  );
}
