import { FaBell } from 'react-icons/fa';

export default function Header() {
  return (
    <header className="bg-white shadow fixed top-0 right-0 left-0 md:left-64 z-50" style={{ boxShadow: '2px 2px 4px rgba(0, 0, 0, 0.1)' }}>
      <div className="container mx-auto flex items-center justify-between p-4">
        {/* Logo (Visible uniquement en mobile) */}
        <h1 className="text-primary font-bold text-xl md:hidden">IthaMedia</h1>

        {/* Barre de recherche (Desktop uniquement) */}
        <div className="hidden md:flex flex-1 mx-4">
          <input
            type="text"
            placeholder="Rechercher un compte ou une chaîne..."
            className="w-full border rounded-lg px-4 py-2"
          />
        </div>

        {/* Actions (Notifications / Filtre / Menu burger) */}
        <div className="flex items-center gap-4">
          {/* Filtre par pays (Visible uniquement en desktop) */}
          <select
            id="country"
            className="border rounded-lg px-3 py-2 text-gray-600 hidden md:block"
          >
            <option value="all">Tous les pays</option>
            <option value="france">France</option>
            <option value="usa">États-Unis</option>
            <option value="canada">Canada</option>
          </select>

          {/* Icône Notification */}
          <button className="text-gray-600">
            <FaBell size={20} />
          </button>

          {/* Menu burger (Visible uniquement en mobile) */}
          <button className="block md:hidden text-primary">
            ☰
          </button>
        </div>
      </div>
    </header>
  );
}
