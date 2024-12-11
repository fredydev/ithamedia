export default function Header() {
    return (
      <header className="bg-white shadow-md sticky top-0 z-50">
        <div className="container mx-auto flex justify-between items-center p-4">
          <h1 className="text-xl font-bold text-primary">IthaMedia</h1>
          <div className="flex items-center gap-4">
            <input
              type="text"
              placeholder="Rechercher un compte ou une chaîne..."
              className="border rounded-lg px-4 py-2 w-80"
            />
            <button className="bg-primary text-white px-4 py-2 rounded-lg">
              Proposer un compte/une chaîne
            </button>
          </div>
        </div>
      </header>
    );
  }
  